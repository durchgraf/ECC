package com.ecc.camera;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.net.Uri;
import android.provider.MediaStore;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

@RunWith(AndroidJUnit4.class)
public class VideoRecorderTest {
    @Test public void surfaceEncoderFinishesWhenStoppedWithoutFrames() throws Exception {
        android.os.StrictMode.setThreadPolicy(new android.os.StrictMode.ThreadPolicy.Builder()
                .permitAll().build());
        ContentResolver resolver = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getContentResolver();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Uri> saved = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        SurfaceVideoRecorder recorder = new SurfaceVideoRecorder(resolver, 640, 480, (uri, message) -> {
            saved.set(uri);
            error.set(message);
            finished.countDown();
        });
        recorder.stop();
        assertTrue("surface encoder should finish an empty recording",
                finished.await(10, TimeUnit.SECONDS));
        assertNull(error.get());
        assertNotNull(saved.get());
        resolver.delete(saved.get(), null, null);
    }

    @Test public void surfaceEncoderFinishesMp4() throws Exception {
        android.os.StrictMode.setThreadPolicy(new android.os.StrictMode.ThreadPolicy.Builder()
                .permitAll().build());
        ContentResolver resolver = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getContentResolver();
        CountDownLatch finished = new CountDownLatch(1);
        AtomicReference<Uri> saved = new AtomicReference<>();
        AtomicReference<String> error = new AtomicReference<>();
        SurfaceVideoRecorder recorder = new SurfaceVideoRecorder(resolver, 640, 480, (uri, message) -> {
            saved.set(uri);
            error.set(message);
            finished.countDown();
        });
        for (int i = 0; i < 8; i++) {
            Bitmap frame = Bitmap.createBitmap(640, 480, Bitmap.Config.ARGB_8888);
            frame.eraseColor(0xff204060 + i * 0x00030303);
            recorder.submit(frame);
        }
        recorder.stop();
        assertTrue("surface encoder should finish", finished.await(10, TimeUnit.SECONDS));
        assertNull(error.get());
        assertNotNull(saved.get());
        try (InputStream stream = resolver.openInputStream(saved.get())) {
            assertNotNull(stream);
            assertTrue(stream.read() >= 0);
        } finally {
            resolver.delete(saved.get(), null, null);
        }
    }


    @Test public void rotatedPreviewKeepsFrameSizeAndClipsCorners() {
        Bitmap source = Bitmap.createBitmap(100, 60, Bitmap.Config.ARGB_8888);
        source.eraseColor(0xffffffff);
        Bitmap preview = FrameRenderer.renderCropped(source, 45f, false);
        try {
            assertEquals(100, preview.getWidth());
            assertEquals(60, preview.getHeight());
            assertEquals(Color.BLACK, preview.getPixel(0, 0));
            assertEquals(Color.WHITE, preview.getPixel(50, 30));
        } finally {
            source.recycle();
            preview.recycle();
        }
    }

    @Test public void savesJpegPhotoToGallery() throws Exception {
        ContentResolver resolver = InstrumentationRegistry.getInstrumentation()
                .getTargetContext().getContentResolver();
        ExecutorService executor = Executors.newSingleThreadExecutor();
        Bitmap bitmap = Bitmap.createBitmap(32, 24, Bitmap.Config.ARGB_8888);
        bitmap.eraseColor(0xff55aa77);
        AtomicReference<Uri> result = new AtomicReference<>();
        AtomicReference<Exception> failure = new AtomicReference<>();
        CountDownLatch done = new CountDownLatch(1);
        executor.execute(() -> {
            try {
                ContentValues values = new ContentValues();
                values.put(MediaStore.Images.Media.DISPLAY_NAME, "ECC-test-photo.jpg");
                values.put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
                values.put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/ECC");
                Uri uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
                if (uri == null) throw new IllegalStateException("Photo insert failed");
                try (java.io.OutputStream stream = resolver.openOutputStream(uri)) {
                    if (stream == null || !bitmap.compress(Bitmap.CompressFormat.JPEG, 95, stream))
                        throw new IllegalStateException("Photo write failed");
                }
                result.set(uri);
            } catch (Exception error) { failure.set(error); }
            finally { done.countDown(); }
        });
        assertTrue(done.await(10, TimeUnit.SECONDS));
        assertNull(failure.get());
        Uri saved = result.get();
        assertNotNull(saved);
        try (InputStream stream = resolver.openInputStream(saved)) {
            assertNotNull(stream);
            assertEquals(0xff, stream.read());
            assertEquals(0xd8, stream.read());
        } finally {
            resolver.delete(saved, null, null);
            bitmap.recycle();
            executor.shutdownNow();
        }
    }

}
