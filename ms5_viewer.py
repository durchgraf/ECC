#!/usr/bin/env python3
# ms5_viewer.py - Live viewer for the MS5 WiFi microscope.
#
# Part of: https://github.com/Fyfar/ms5-wifi-microscope   (MIT License)
# Full protocol documentation is in README.md.
#
# Reverse-engineered i4season/Suear protocol (libWifiCamera.so). Pulls the
# camera's 1280x720 MJPEG-over-UDP stream and re-serves it as MJPEG-over-HTTP
# so you can watch it in a browser, VLC, ffplay, or OBS.
#
#   Run (while connected to the camera's WiFi AP):
#       python3 ms5_viewer.py
#   Then:
#       Browser:     http://127.0.0.1:45100
#       VLC/ffplay:  http://127.0.0.1:45100/stream
#       Snapshot:    http://127.0.0.1:45100/snapshot
#
# Stdlib only, py3.7+. No internet needed (talks only to 192.168.1.1).

import http.server
import json
import math
import socket
import struct
import sys
import threading
import time

CAM       = "192.168.1.1"
P_CMD     = 10005          # command channel (UDP)
P_OV      = 10006          # OpenVideo / stream-init (UDP)
HTTP_HOST = "127.0.0.1"
HTTP_PORT = 45100
CHUNK_HDR = 16             # bytes of stream-chunk header before JPEG payload


def decode_orientation(packet):
    """Decode experimental Soulear packed 10-bit sign/magnitude axes.

    Inferred from physical rotation measurements; optical-axis mapping and
    correction polarity still require visual confirmation on this device.
    """
    if len(packet) < CHUNK_HDR:
        return None
    word = struct.unpack_from("<I", packet, 6)[0]
    axes = []
    for shift in (0, 10, 20):
        value = (word >> shift) & 0x3ff
        axes.append(-(value & 0x1ff) if value & 0x200 else value)
    x, y, z = axes
    angle = math.degrees(math.atan2(x, y)) if math.hypot(x, y) >= 16 else None
    return {"axes": axes, "angle": angle}


class MS5Client:
    def __init__(self):
        self.vid = None
        self.cmd = None
        self.ov = None
        self.port = None             # our video recv port (announced to camera)
        self.device = {}
        self.width = 0
        self.height = 0
        self.running = False
        self.frames = 0
        self.last_rx = 0.0
        self._cond = threading.Condition()
        self._latest = None
        self._seq = 0
        self._last_err = None        # last OSError seen by _req, if any
        self.orientation = None
        self.orientation_at = None

    # ---- protocol helpers ----
    @staticmethod
    def _msg(msg_type, mid, payload=b"", unk=1, length=None):
        if length is None:
            length = len(payload)
        return struct.pack("<IHHBBH", 0xffeeffee, mid, msg_type, unk, 0, length) + payload

    def _req(self, sock, port, msg_type, mid, payload=b"", length=None, retries=8, timeout=2):
        for _ in range(retries):
            try:
                sock.sendto(self._msg(msg_type, mid, payload, length=length), (CAM, port))
                sock.settimeout(timeout)
                resp, src = sock.recvfrom(4096)
                if (src[0] == CAM and len(resp) >= 12
                        and resp[:4] == b"\xee\xff\xee\xff"):
                    return resp
            except socket.timeout:
                pass
            except OSError as e:
                # The OS actively refused the packet (e.g. EHOSTUNREACH) --
                # distinct from plain silence, and worth surfacing as such:
                # this is what a local Local-Network-permission/firewall
                # block looks like, and it happens even though the camera
                # itself replies fine to ping. See connect()'s error message.
                self._last_err = e
                break
            mid = (mid + 1) & 0xffff
        return None

    def connect(self):
        # video recv socket on an OS-assigned ephemeral port
        self.vid = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        self.vid.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
        self.vid.setsockopt(socket.SOL_SOCKET, socket.SO_RCVBUF, 4 * 1024 * 1024)
        self.vid.bind(("0.0.0.0", 0))
        self.port = self.vid.getsockname()[1]

        # command channel: GetDeviceInfo (also warms up the flaky-first-packet service)
        self.cmd = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
        resp = self._req(self.cmd, P_CMD, 0x0001, 1, retries=10)
        if resp and len(resp) >= 12 + 113:
            d = resp[12:]
            self.device = {
                "vendor":  d[1:33].split(b"\0")[0].decode("ascii", "replace"),
                "product": d[33:65].split(b"\0")[0].decode("ascii", "replace"),
                "fw":      d[65:81].split(b"\0")[0].decode("ascii", "replace"),
                "ssid":    d[81:113].split(b"\0")[0].decode("ascii", "replace"),
            }
        elif resp is None:
            if self._last_err is not None:
                raise IOError(
                    "OS refused UDP to camera at %s:%d (%s).\n"
                    "This is a LOCAL block, not the camera or WiFi -- check:\n"
                    "  ping %s   (if that works, the camera is fine and reachable)\n"
                    "  macOS: System Settings > Privacy & Security > Local Network -- "
                    "toggle this terminal app off/on and fully quit+relaunch it "
                    "(a stale grant here is a known quirk, esp. with iTerm2)\n"
                    "  also check any third-party firewall/VPN/content-filter"
                    % (CAM, P_CMD, self._last_err, CAM))
            raise IOError("No response from camera at %s:%d - are you on the camera WiFi?"
                          % (CAM, P_CMD))

        # OpenVideo carrying our recv port -> camera starts streaming to self.port
        self.ov = self.vid  # Use the same flow for firewall connection tracking.
        self._open_video()

    def _open_video(self, retries=6, timeout=2):
        self._req(self.ov, P_OV, 0x0004, 1, payload=struct.pack("<H", self.port),
                  length=0, retries=retries, timeout=timeout)

    # ---- receive / reassemble loop (runs in its own thread) ----
    def run(self):
        self.running = True
        self.last_rx = time.time()
        last_rearm = 0.0
        cur = None
        chunks = {}
        frame_orientation = None
        self.vid.settimeout(0.5)
        while self.running:
            try:
                data, src = self.vid.recvfrom(8192)
            except socket.timeout:
                # stall watchdog: nudge the camera if no data for a while
                now = time.time()
                if now - self.last_rx > 1.0 and now - last_rearm > 1.0:
                    self._open_video(retries=2, timeout=0.4)
                    last_rearm = now
                continue
            except OSError:
                break
            if src[0] != CAM or len(data) < CHUNK_HDR:
                continue
            self.last_rx = time.time()
            if data[:4] == b"\xee\xff\xee\xff":
                continue
            seq = struct.unpack_from(">H", data, 0)[0]
            self.width = struct.unpack_from("<H", data, 12)[0]
            self.height = struct.unpack_from("<H", data, 14)[0]
            payload = data[CHUNK_HDR:]
            if payload.startswith(b"\xff\xd8"):
                chunks = {}
                cur = seq
                frame_orientation = decode_orientation(data)
            elif cur is None or seq != ((cur + 1) & 0xffff):
                chunks = {}
                cur = None
                continue
            cur = seq
            chunks[len(chunks)] = payload
            if b"\xff\xd9" in payload:
                self._emit(chunks, frame_orientation)
                chunks = {}
                cur = None

    def _emit(self, chunks, orientation=None):
        if not chunks:
            return
        jpg = b"".join(chunks[k] for k in sorted(chunks))
        if jpg[:2] != b"\xff\xd8":
            return
        e = jpg.rfind(b"\xff\xd9")
        if e == -1:
            return
        jpg = jpg[:e + 2]
        with self._cond:
            self._latest = jpg
            self.orientation = orientation
            self.orientation_at = time.monotonic()
            self._seq += 1
            self.frames += 1
            self._cond.notify_all()

    def get_frame(self, last_seq, timeout=2.0):
        with self._cond:
            if self._seq == last_seq or self._latest is None:
                self._cond.wait(timeout)
            return self._latest, self._seq


# ----------------------------- HTTP server -----------------------------------
class Handler(http.server.BaseHTTPRequestHandler):
    client: MS5Client

    def log_message(self, *a):
        pass

    def do_GET(self):
        if self.path in ("/", "/index.html"):
            self._page()
        elif self.path == "/snapshot":
            self._snapshot()
        elif self.path == "/stream":
            self._stream()
        elif self.path == "/orientation":
            self._orientation()
        else:
            self.send_response(404)
            self.end_headers()

    def _page(self):
        d = self.client.device
        info = "%s %s  fw %s  (%s)" % (d.get("vendor", "?"), d.get("product", "MS5"),
                                       d.get("fw", "?"), d.get("ssid", "?"))
        html = (
            "<!doctype html><html><head><title>ECC — Live Camera</title>"
            "<meta name='viewport' content='width=device-width,initial-scale=1'>"
            "<style>*{box-sizing:border-box}body{min-height:100vh;margin:0;background:#090b10;color:#eef1f6;"
            "font:14px/1.5 Inter,ui-sans-serif,system-ui,sans-serif}"
            ".shell{max-width:1180px;margin:auto;padding:24px 28px 20px}"
            "header{height:56px;display:flex;align-items:center;justify-content:space-between;margin-bottom:20px}"
            ".brand{display:flex;align-items:center;gap:12px;font-size:15px;font-weight:600;letter-spacing:-.02em}"
            ".brand-mark{width:30px;height:30px;border:1px solid #303743;border-radius:9px;display:grid;"
            "place-items:center;color:#80e0c1;background:#111820;font-size:17px}"
            ".device{color:#8c96a6;font-size:12px;text-align:right}"
            ".stage{min-height:min(72vh,760px);display:grid;place-items:center;overflow:hidden;position:relative;"
            "border:1px solid #252b35;border-radius:16px;background:radial-gradient(ellipse at center,#151a22 0,#0d1016 65%%);"
            "box-shadow:0 18px 55px #0005}"
            ".stage:before{content:'';position:absolute;inset:0;opacity:.18;pointer-events:none;background-image:"
            "linear-gradient(#78839912 1px,transparent 1px),linear-gradient(90deg,#78839912 1px,transparent 1px);"
            "background-size:32px 32px;mask-image:radial-gradient(ellipse,black,transparent 78%%)}"
            "#rotation{position:relative;display:inline-block;transform-origin:center;line-height:0;transition:none}"
            "#video{display:block;max-width:min(88vw,1080px);max-height:68vh;width:auto;height:auto;"
            "object-fit:contain;border-radius:4px;box-shadow:0 8px 35px #0008}img.mirrored{transform:scaleX(-1)}"
            ".toolbar{display:flex;align-items:center;justify-content:space-between;gap:14px;padding:14px 2px 10px}"
            ".group{display:flex;align-items:center;gap:8px;flex-wrap:wrap}"
            "button{min-height:40px;padding:0 14px;cursor:pointer;background:#11151c;color:#cbd2de;"
            "border:1px solid #2a313d;border-radius:9px;font:500 13px/1.2 Inter,ui-sans-serif,system-ui,sans-serif;"
            "transition:background .15s,border-color .15s,color .15s}"
            "button:hover:not(:disabled){background:#1a202a;border-color:#465264;color:#fff}"
            "button:focus-visible{outline:2px solid #75d9bb;outline-offset:2px}"
            "button:disabled{opacity:.42;cursor:not-allowed}button[aria-pressed=true]{color:#8ae2c3;"
            "border-color:#376451;background:#12211d}button.primary{background:#b4f2dc;color:#10231c;"
            "border-color:#b4f2dc;font-weight:650}button.primary:hover{background:#d0ffed}"
            "#record.recording{background:#32191d;border-color:#733a43;color:#ffabb2}"
            ".status{display:flex;align-items:center;gap:8px;color:#8993a3;font-size:12px;min-height:25px}"
            ".dot{width:7px;height:7px;border-radius:50%%;background:#728094}"
            ".status.live .dot{background:#55d6a8;box-shadow:0 0 10px #55d6a866}"
            ".meta{color:#667181;font-size:11px;text-align:right;margin:4px 2px 0}"
            "@media(max-width:640px){.shell{padding:12px}.stage{min-height:58vh;border-radius:12px}"
            ".toolbar{align-items:flex-start;flex-direction:column}.group{width:100%%}button{flex:1;padding:0 9px}"
            "#video{max-width:90vw;max-height:54vh}.device{max-width:55%%;font-size:11px}}"
            "</style></head><body><main class='shell'><header><div class='brand'>"
            "<span class='brand-mark'>⌖</span><span>ECC <span style='color:#8993a3;font-weight:400'>Live</span></span></div>"
            "<div class='device'>%s<br><span style='color:#657080'>%dx%d</span></div></header>"
            "<section class='stage' aria-label='Live camera feed'><div id='rotation' style='transform-origin:center'>"
            "<img id='video' src='/stream' alt='Live camera feed'></div></section>"
            "<div class='toolbar'><div class='group'>"
            "<button id='mirror' type='button' aria-pressed='false'>Mirror: off</button>"
            "<button id='stabilize' type='button' aria-pressed='false' disabled>Align (test): off</button>"
            "<button id='zero' type='button' disabled>Calibrate this as down</button>"
            "</div><div class='group'>"
            "<button id='photo' type='button'>Take photo</button>"
            "<button id='record' class='primary' type='button'>Start recording</button>"
            "</div></div><div class='status' id='status'><span class='dot'></span><span id='orientation-status'>Waiting for orientation data…</span></div>"
            "<div class='meta'>Photo: JPEG · Video: WebM</div></main>"
            "<script>const button=document.getElementById('mirror');"
            "button.addEventListener('click',()=>{"
            "const active=document.getElementById('video').classList.toggle('mirrored');"
            "button.setAttribute('aria-pressed',String(active));"
            "button.textContent=active?'Mirror: on':'Mirror: off';"
            "applyRotation();"
            "});</script>"
            """<script>
            const stabilizer=document.getElementById('stabilize');
            const zero=document.getElementById('zero');
            const rotation=document.getElementById('rotation');
            const statusLine=document.getElementById('orientation-status');
            const status=document.getElementById('status');
            let active=false, polarity=-1, reference=null, angle=null, unwrapped=null;
            let displayed=null, animation=null;
            function applyRotation(){
                const mirrorSign=document.getElementById('video').classList.contains('mirrored')?-1:1;
                if(active && unwrapped!==null){
                    displayed=displayed===null?unwrapped:displayed+(unwrapped-displayed)*0.12;
                    animation=requestAnimationFrame(applyRotation);
                }else{
                    displayed=unwrapped;
                    animation=null;
                }
                const correction=active && reference!==null && displayed!==null
                    ? mirrorSign*polarity*(displayed-reference) : 0;
                rotation.style.transform='rotate('+correction+'deg)';
            }
            stabilizer.addEventListener('click',()=>{
                active=!active;
                if(active && reference===null) reference=unwrapped;
                stabilizer.setAttribute('aria-pressed',String(active));
                stabilizer.textContent='Align (test): '+(active?'on':'off');
                applyRotation();
            });
            zero.addEventListener('click',()=>{
                reference=unwrapped;
                statusLine.textContent='Reference saved — this direction is now down';
                applyRotation();
            });
            const video=document.getElementById('video');
            const photo=document.getElementById('photo');
            const record=document.getElementById('record');
            let recorder=null, recordingChunks=[], recordingCanvas=null, recordingAnimation=null;
            function drawCaptureFrame(canvas){
                const w=video.naturalWidth, h=video.naturalHeight;
                if(!w||!h)return;
                const transform=getComputedStyle(rotation).transform;
                const matrix=transform==='none'?null:new DOMMatrix(transform);
                const radians=matrix?Math.atan2(matrix.b,matrix.a):0;
                const outW=Math.ceil(Math.abs(w*Math.cos(radians))+Math.abs(h*Math.sin(radians)));
                const outH=Math.ceil(Math.abs(h*Math.cos(radians))+Math.abs(w*Math.sin(radians)));
                if(canvas.width!==outW||canvas.height!==outH){canvas.width=outW;canvas.height=outH;}
                const ctx=canvas.getContext('2d');
                ctx.clearRect(0,0,outW,outH);ctx.save();ctx.translate(outW/2,outH/2);
                ctx.rotate(radians);
                if(video.classList.contains('mirrored'))ctx.scale(-1,1);
                ctx.drawImage(video,-w/2,-h/2,w,h);ctx.restore();
            }
            function downloadBlob(blob,name){
                const link=document.createElement('a');link.href=URL.createObjectURL(blob);link.download=name;
                link.click();setTimeout(()=>URL.revokeObjectURL(link.href),1000);
            }
            function filename(extension){return 'soulear-'+new Date().toISOString().replace(/[:.]/g,'-')+'.'+extension;}
            photo.addEventListener('click',()=>{
                const canvas=document.createElement('canvas');drawCaptureFrame(canvas);
                canvas.toBlob(blob=>{if(blob)downloadBlob(blob,filename('jpg'));},'image/jpeg',0.95);
            });
            function drawRecording(){
                if(!recorder||recorder.state!=='recording')return;
                drawCaptureFrame(recordingCanvas);recordingAnimation=requestAnimationFrame(drawRecording);
            }
            record.addEventListener('click',()=>{
                if(recorder&&recorder.state==='recording'){recorder.stop();return;}
                if(!window.MediaRecorder||!HTMLCanvasElement.prototype.captureStream){
                    statusLine.textContent='Video recording is not supported by this browser';return;
                }
                recordingCanvas=document.createElement('canvas');drawCaptureFrame(recordingCanvas);
                const stream=recordingCanvas.captureStream(12);recordingChunks=[];
                recorder=new MediaRecorder(stream,{mimeType:'video/webm'});
                recorder.ondataavailable=event=>{if(event.data.size)recordingChunks.push(event.data);};
                recorder.onstop=()=>{
                    cancelAnimationFrame(recordingAnimation);stream.getTracks().forEach(track=>track.stop());
                    downloadBlob(new Blob(recordingChunks,{type:'video/webm'}),filename('webm'));
                    record.textContent='Start recording';record.classList.remove('recording');recorder=null;
                };
                recorder.start();record.textContent='Stop recording';record.classList.add('recording');drawRecording();
            });
            async function pollOrientation(){
                try{
                    const response=await fetch('/orientation',{cache:'no-store',signal:AbortSignal.timeout(2000)});
                    if(!response.ok) throw new Error('HTTP '+response.status);
                    const data=await response.json();
                    const valid=data.available && Number.isFinite(data.angle);
                    status.classList.toggle('live',valid);
                    stabilizer.disabled=!valid;
                    zero.disabled=!valid;
                    if(valid){
                        const next=data.angle;
                        const delta=angle===null?0:((next-angle+540)%%360)-180;
                        unwrapped=unwrapped===null?next:unwrapped+delta;
                        angle=next;
                        if(active && animation===null) animation=requestAnimationFrame(applyRotation);
                        else if(!active) applyRotation();
                        statusLine.textContent='Orientation: '+next.toFixed(1)+'° — '+
                        (reference===null?'Calibrate in the desired upright position':'Reference active');
                    }else{
                        statusLine.textContent=data.available
                            ? 'Orientation unavailable — tilt the camera slightly'
                            : 'No current orientation data — alignment paused';
                    }
                }catch(error){
                    stabilizer.disabled=true;zero.disabled=true;
                    statusLine.textContent='Orientation data unavailable — alignment paused';
                }finally{setTimeout(pollOrientation,70);}
            }
            pollOrientation();
            </script></body></html>"""
            % (info, self.client.width, self.client.height)
        ).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "text/html; charset=utf-8")
        self.send_header("Content-Length", str(len(html)))
        self.end_headers()
        self.wfile.write(html)

    def _orientation(self):
        with self.client._cond:
            orientation = self.client.orientation
            age = (time.monotonic() - self.client.orientation_at
                   if self.client.orientation_at is not None else None)
            fresh = orientation is not None and age is not None and age < 2.0
            data = {"available": fresh,
                    "angle": orientation["angle"] if fresh and orientation is not None else None,
                    "axes": orientation["axes"] if fresh and orientation is not None else None,
                    "frame": self.client._seq, "age": age}
        body = json.dumps(data).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _snapshot(self):
        jpg, _ = self.client.get_frame(-1, timeout=3.0)
        if not jpg:
            self.send_response(503)
            self.end_headers()
            return
        self.send_response(200)
        self.send_header("Content-Type", "image/jpeg")
        self.send_header("Content-Length", str(len(jpg)))
        self.send_header("Cache-Control", "no-store")
        self.end_headers()
        self.wfile.write(jpg)

    def _stream(self):
        self.send_response(200)
        self.send_header("Age", "0")
        self.send_header("Cache-Control", "no-cache, private")
        self.send_header("Pragma", "no-cache")
        self.send_header("Content-Type", "multipart/x-mixed-replace; boundary=frame")
        self.end_headers()
        last = -1
        try:
            while True:
                jpg, last = self.client.get_frame(last, timeout=2.0)
                if jpg is None:
                    continue
                self.wfile.write(b"--frame\r\n")
                self.wfile.write(b"Content-Type: image/jpeg\r\n")
                self.wfile.write(("Content-Length: %d\r\n\r\n" % len(jpg)).encode("ascii"))
                self.wfile.write(jpg)
                self.wfile.write(b"\r\n")
        except (BrokenPipeError, ConnectionResetError, OSError):
            return


class ThreadingHTTPServer(http.server.ThreadingHTTPServer):
    daemon_threads = True
    allow_reuse_address = True


def fps_reporter(client):
    last = 0
    while True:
        time.sleep(5)
        cur = client.frames
        sys.stderr.write("  [stream] %d frames total  (~%.1f fps)  %dx%d\n"
                         % (cur, (cur - last) / 5.0, client.width, client.height))
        last = cur


def main():
    print("Connecting to MS5 at %s ..." % CAM)
    client = MS5Client()
    try:
        client.connect()
    except IOError as e:
        print("ERROR: %s" % e)
        sys.exit(1)
    d = client.device
    print("Device: %s %s  fw=%s  ssid=%s" % (d.get("vendor", "?"), d.get("product", "?"),
                                             d.get("fw", "?"), d.get("ssid", "?")))
    print("Video recv port: %d" % client.port)

    threading.Thread(target=client.run, daemon=True).start()
    threading.Thread(target=fps_reporter, args=(client,), daemon=True).start()

    Handler.client = client
    httpd = ThreadingHTTPServer((HTTP_HOST, HTTP_PORT), Handler)
    url = "http://%s:%d" % (HTTP_HOST, HTTP_PORT)
    print("\n  Live view : %s" % url)
    print("  VLC/ffplay: %s/stream" % url)
    print("  Snapshot  : %s/snapshot" % url)
    print("\nPress Ctrl+C to stop.")
    try:
        httpd.serve_forever()
    except KeyboardInterrupt:
        print("\nStopping.")
        client.running = False


if __name__ == "__main__":
    main()
