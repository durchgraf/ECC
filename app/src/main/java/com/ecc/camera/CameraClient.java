package com.ecc.camera;

import android.os.SystemClock;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;

final class CameraClient {
    interface Listener {
        void onState(String message, boolean connected);
        void onFrame(byte[] jpeg, Float angle, int width, int height);
    }

    private final Listener listener;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private volatile DatagramSocket videoSocket;
    private volatile DatagramSocket commandSocket;
    private Thread worker;
    private int videoPort;
    private long lastPacketAt;
    private long lastRearmAt;

    CameraClient(Listener listener) {
        this.listener = listener;
    }

    synchronized void start() {
        if (running.get()) return;
        running.set(true);
        worker = new Thread(this::connectAndReceive, "ecc-camera-network");
        worker.start();
    }

    synchronized void stop() {
        running.set(false);
        close(videoSocket);
        close(commandSocket);
        if (worker != null) worker.interrupt();
        worker = null;
        listener.onState("Disconnected", false);
    }

    private void connectAndReceive() {
        try {
            InetAddress camera = InetAddress.getByName(CameraProtocol.CAMERA_IP);
            videoSocket = new DatagramSocket(null);
            videoSocket.setReuseAddress(true);
            videoSocket.setReceiveBufferSize(4 * 1024 * 1024);
            videoSocket.bind(new InetSocketAddress(0));
            videoPort = videoSocket.getLocalPort();
            commandSocket = new DatagramSocket();

            listener.onState("Connecting to camera…", false);
            byte[] deviceInfo = request(commandSocket, camera, CameraProtocol.COMMAND_PORT,
                    0x0001, 1, new byte[0], 0, 10, 1000);
            if (deviceInfo == null) throw new IOException("No reply from camera. Check its Wi-Fi connection.");
            String model = parseModel(deviceInfo);
            listener.onState("Starting live view" + (model.isEmpty() ? "…" : " · " + model), false);

            byte[] port = {(byte) (videoPort & 0xff), (byte) ((videoPort >>> 8) & 0xff)};
            request(videoSocket, camera, CameraProtocol.OPEN_VIDEO_PORT,
                    0x0004, 1, port, 0, 6, 1000);
            if (!running.get()) return;
            videoSocket.setSoTimeout(500);
            lastPacketAt = SystemClock.elapsedRealtime();
            listener.onState("Connected · waiting for picture…", true);
            receiveFrames(camera);
        } catch (Exception error) {
            if (running.get()) listener.onState(userMessage(error), false);
        } finally {
            running.set(false);
            close(videoSocket);
            close(commandSocket);
            videoSocket = null;
            commandSocket = null;
        }
    }

    private void receiveFrames(InetAddress camera) throws IOException {
        CameraProtocol.FrameAssembler assembler = new CameraProtocol.FrameAssembler();
        byte[] buffer = new byte[8192];
        DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
        while (running.get()) {
            try {
                packet.setLength(buffer.length);
                videoSocket.receive(packet);
                if (!camera.equals(packet.getAddress()) || packet.getLength() < CameraProtocol.CHUNK_HEADER_SIZE) continue;
                if (packet.getData()[0] == (byte) 0xee && packet.getData()[1] == (byte) 0xff
                        && packet.getData()[2] == (byte) 0xee && packet.getData()[3] == (byte) 0xff) continue;
                lastPacketAt = SystemClock.elapsedRealtime();
                CameraProtocol.Frame frame = assembler.accept(packet.getData(), packet.getLength());
                if (frame != null) listener.onFrame(frame.jpeg, frame.angle, frame.width, frame.height);
            } catch (SocketTimeoutException timeout) {
                long now = SystemClock.elapsedRealtime();
                if (now - lastPacketAt > 1200 && now - lastRearmAt > 1200) {
                    lastRearmAt = now;
                    byte[] port = {(byte) (videoPort & 0xff), (byte) ((videoPort >>> 8) & 0xff)};
                    request(videoSocket, camera, CameraProtocol.OPEN_VIDEO_PORT,
                            0x0004, 1, port, 0, 2, 400);
                }
            }
        }
    }

    private byte[] request(DatagramSocket socket, InetAddress camera, int port,
                           int type, int messageId, byte[] payload, int declaredLength,
                           int retries, int timeoutMillis) throws IOException {
        byte[] responseBuffer = new byte[4096];
        DatagramPacket response = new DatagramPacket(responseBuffer, responseBuffer.length);
        for (int attempt = 0; attempt < retries && running.get(); attempt++) {
            byte[] request = CameraProtocol.message(type, messageId, payload, declaredLength);
            socket.send(new DatagramPacket(request, request.length, camera, port));
            socket.setSoTimeout(timeoutMillis);
            try {
                response.setLength(responseBuffer.length);
                socket.receive(response);
                if (camera.equals(response.getAddress()) && response.getLength() >= 12
                        && responseBuffer[0] == (byte) 0xee && responseBuffer[1] == (byte) 0xff
                        && responseBuffer[2] == (byte) 0xee && responseBuffer[3] == (byte) 0xff) {
                    return java.util.Arrays.copyOf(responseBuffer, response.getLength());
                }
            } catch (SocketTimeoutException ignored) {
                // Retry: this camera frequently drops the first command after idle.
            }
            messageId = (messageId + 1) & 0xffff;
        }
        return null;
    }

    private static String parseModel(byte[] response) {
        if (response.length < 12 + 65) return "";
        int start = 12 + 33;
        int end = Math.min(response.length, start + 32);
        int zero = start;
        while (zero < end && response[zero] != 0) zero++;
        return new String(response, start, zero - start, StandardCharsets.US_ASCII).trim();
    }

    private static String userMessage(Exception error) {
        String message = error.getMessage();
        if (message != null && message.startsWith("No reply from camera")) return message;
        if (error instanceof SocketException && !"Socket closed".equals(message)) {
            return "Network error. Connect to the microscope’s Wi-Fi and try again.";
        }
        return "Could not connect to the camera. Check its Wi-Fi and try again.";
    }

    private static void close(DatagramSocket socket) {
        if (socket != null) socket.close();
    }
}
