package com.termant.app;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Bitmap;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Takes a picture of the screen through the screen control service. Android
 * can do this from version 11 on; the code is in its own class so that older
 * phones never load it.
 */
final class ScreenPicture {
    private ScreenPicture() {
    }

    static Bitmap take(AccessibilityService service) throws Exception {
        for (int attempt = 0; ; attempt++) {
            final CountDownLatch done = new CountDownLatch(1);
            final Bitmap[] got = {null};
            final int[] error = {0};
            service.takeScreenshot(android.view.Display.DEFAULT_DISPLAY, new java.util.concurrent.Executor() {
                @Override
                public void execute(Runnable r) {
                    r.run();
                }
            }, new AccessibilityService.TakeScreenshotCallback() {
                @Override
                public void onSuccess(AccessibilityService.ScreenshotResult result) {
                    try {
                        android.hardware.HardwareBuffer buffer = result.getHardwareBuffer();
                        Bitmap hard = Bitmap.wrapHardwareBuffer(buffer, result.getColorSpace());
                        if (hard != null) got[0] = hard.copy(Bitmap.Config.ARGB_8888, false);
                        buffer.close();
                    } catch (Exception e) {
                        // reported below as no picture
                    }
                    done.countDown();
                }

                @Override
                public void onFailure(int code) {
                    error[0] = code;
                    done.countDown();
                }
            });
            if (!done.await(8, TimeUnit.SECONDS)) throw new java.io.IOException("Android gave no picture of the screen");
            if (got[0] != null) return got[0];
            if (error[0] == 3 && attempt < 3) { // Android allows one picture a second
                Thread.sleep(1100);
                continue;
            }
            throw new java.io.IOException(error[0] == 6 ? "the app on screen forbids pictures of itself"
                    : "Android gave no picture of the screen (code " + error[0] + ")");
        }
    }
}
