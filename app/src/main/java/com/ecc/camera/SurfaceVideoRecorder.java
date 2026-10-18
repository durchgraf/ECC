package com.ecc.camera;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaFormat;
import android.media.MediaMuxer;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class SurfaceVideoRecorder {
    interface Listener { void onFinished(Uri savedUri, String error); }

    private static final int FPS = 8;
    private final ContentResolver resolver;
    private final Listener listener;
    private final Uri uri;
    private final ParcelFileDescriptor descriptor;
    private final MediaCodec encoder;
    private final MediaMuxer muxer;
    private final Surface inputSurface;
    private final int width, height;
    private final ArrayBlockingQueue<Bitmap> frames = new ArrayBlockingQueue<>(2);
    private final AtomicReference<String> failure = new AtomicReference<>();
    private volatile boolean stopping;
    private volatile long stoppedAtNanos;

    SurfaceVideoRecorder(ContentResolver resolver, int width, int height, Listener listener) throws IOException {
        this.resolver = resolver;
        this.listener = listener;
        this.width = Math.max(2, width & ~1);
        this.height = Math.max(2, height & ~1);
        ContentValues values = new ContentValues();
        values.put(MediaStore.Video.Media.DISPLAY_NAME, "ECC-" + System.currentTimeMillis() + ".mp4");
        values.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
        values.put(MediaStore.Video.Media.RELATIVE_PATH, "Movies/ECC");
        values.put(MediaStore.Video.Media.IS_PENDING, 1);
        Uri created = resolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values);
        if (created == null) throw new IOException("Could not create video in the media library.");
        uri = created;
        ParcelFileDescriptor opened = resolver.openFileDescriptor(uri, "w");
        if (opened == null) {
            resolver.delete(uri, null, null);
            throw new IOException("Could not open the video file.");
        }
        descriptor = opened;
        MediaCodec createdEncoder = null;
        MediaMuxer createdMuxer = null;
        Surface createdSurface = null;
        try {
            MediaFormat format = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC,
                    this.width, this.height);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE, Math.max(600_000, this.width * this.height * 2));
            format.setInteger(MediaFormat.KEY_FRAME_RATE, FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 2);
            createdEncoder = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC);
            createdEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            createdSurface = createdEncoder.createInputSurface();
            createdMuxer = new MediaMuxer(descriptor.getFileDescriptor(), MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            createdEncoder.start();
        } catch (RuntimeException | IOException error) {
            if (createdSurface != null) createdSurface.release();
            if (createdEncoder != null) createdEncoder.release();
            if (createdMuxer != null) createdMuxer.release();
            descriptor.close();
            resolver.delete(uri, null, null);
            throw new IOException("This device could not start video recording.", error);
        }
        encoder = createdEncoder;
        muxer = createdMuxer;
        inputSurface = createdSurface;
        new Thread(this::drainOutput, "ecc-video-output").start();
        new Thread(this::feedFrames, "ecc-video-input").start();
    }

    synchronized void submit(Bitmap bitmap) {
        if (stopping || bitmap == null) {
            if (bitmap != null && !bitmap.isRecycled()) bitmap.recycle();
            return;
        }
        if (!frames.offer(bitmap)) {
            Bitmap dropped = frames.poll();
            if (dropped != null && !dropped.isRecycled()) dropped.recycle();
            frames.offer(bitmap);
        }
    }

    synchronized void stop() {
        if (stopping) return;
        stoppedAtNanos = System.nanoTime();
        stopping = true;
    }

    private void feedFrames() {
        try {
            while (!stopping || !frames.isEmpty()) {
                Bitmap bitmap = frames.poll(100, TimeUnit.MILLISECONDS);
                if (bitmap == null) continue;
                try {
                    Canvas canvas = inputSurface.lockCanvas(null);
                    try {
                        canvas.drawColor(Color.BLACK);
                        canvas.drawBitmap(bitmap, null, new Rect(0, 0, width, height), null);
                    } finally {
                        inputSurface.unlockCanvasAndPost(canvas);
                    }
                } finally {
                    if (!bitmap.isRecycled()) bitmap.recycle();
                }
            }
        } catch (Exception error) {
            failure.compareAndSet(null, message(error));
        } finally {
            try { encoder.signalEndOfInputStream(); }
            catch (RuntimeException error) { failure.compareAndSet(null, message(error)); }
            inputSurface.release();
        }
    }

    private void drainOutput() {
        boolean muxerStarted = false;
        boolean outputEnded = false;
        int track = -1;
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        try {
            while (!outputEnded) {
                if (stopping && stoppedAtNanos != 0
                        && System.nanoTime() - stoppedAtNanos > TimeUnit.SECONDS.toNanos(20)) {
                    throw new IOException("Video encoder timed out while finalizing the MP4.");
                }
                int index = encoder.dequeueOutputBuffer(info, 10_000);
                if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = muxer.addTrack(encoder.getOutputFormat());
                    muxer.start();
                    muxerStarted = true;
                } else if (index >= 0) {
                    ByteBuffer output = encoder.getOutputBuffer(index);
                    if (output != null && info.size > 0 && muxerStarted
                            && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) == 0) {
                        output.position(info.offset);
                        output.limit(info.offset + info.size);
                        muxer.writeSampleData(track, output, info);
                    }
                    outputEnded = (info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0;
                    encoder.releaseOutputBuffer(index, false);
                }
            }
        } catch (Exception error) {
            failure.compareAndSet(null, message(error));
        } finally {
            try { encoder.stop(); } catch (RuntimeException ignored) {}
            try { encoder.release(); } catch (RuntimeException ignored) {}
            if (muxerStarted) try { muxer.stop(); } catch (RuntimeException ignored) {}
            try { muxer.release(); } catch (RuntimeException ignored) {}
            try { descriptor.close(); } catch (IOException ignored) {}
            Uri result = null;
            String error = failure.get();
            if (error == null) {
                try {
                    ContentValues completed = new ContentValues();
                    completed.put(MediaStore.Video.Media.IS_PENDING, 0);
                    if (resolver.update(uri, completed, null, null) <= 0)
                        throw new IOException("Could not finish saving the video.");
                    result = uri;
                } catch (Exception updateError) {
                    error = message(updateError);
                }
            }
            if (result == null) {
                try { resolver.delete(uri, null, null); } catch (RuntimeException ignored) {}
            }
            listener.onFinished(result, error);
        }
    }

    private static String message(Exception error) {
        return error.getMessage() == null ? "Video encoding failed." : error.getMessage();
    }
}
