package com.termant.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Puts a picture made in Ubuntu (see `draw` in guest/kit) into the phone's gallery, in
 * Pictures/Termant.
 */
final class Gallery {
    static final String FOLDER = "Termant";

    private Gallery() {
    }

    /**
     * "image/jpeg" and the like when these bytes are something the gallery shows, else null.
     */
    static String kind(byte[] data) {
        if (data.length > 3 && (data[0] & 0xff) == 0xff && (data[1] & 0xff) == 0xd8 && (data[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (data.length > 8 && (data[0] & 0xff) == 0x89 && data[1] == 'P' && data[2] == 'N' && data[3] == 'G') {
            return "image/png";
        }
        if (data.length > 12 && data[0] == 'R' && data[1] == 'I' && data[2] == 'F' && data[3] == 'F'
                && data[8] == 'W' && data[9] == 'E' && data[10] == 'B' && data[11] == 'P') {
            return "image/webp";
        }
        if (data.length > 6 && data[0] == 'G' && data[1] == 'I' && data[2] == 'F') return "image/gif";
        return null;
    }

    /**
     * Saves the picture. Returns {where it is, as the user would say it; its address on the phone or ""}.
     * Throws with a sentence for the user when it cannot be saved.
     */
    static String[] save(Context c, String name, byte[] data) throws PhoneBridge.Refused {
        String kind = kind(data);
        if (kind == null) throw new PhoneBridge.Refused("phone gallery: this file is not a picture (JPEG, PNG, WebP or GIF)");
        String ending = kind.equals("image/jpeg") ? "jpg" : kind.substring(kind.indexOf('/') + 1);
        String base = new File(name).getName().replaceAll("\\.[A-Za-z0-9]{1,5}$", "")
                .replaceAll("[^A-Za-z0-9._-]+", "-").replaceAll("^[-.]+", "");
        if (base.isEmpty()) base = "picture";
        if (base.length() > 80) base = base.substring(0, 80);
        String file = base + "." + ending;
        String folderName = Environment.DIRECTORY_PICTURES;
        String place = folderName + "/" + FOLDER + "/" + file;

        Exception first = null;
        if (Build.VERSION.SDK_INT >= 29) {
            // Android 10 and later: an app may add its own pictures to the gallery without being allowed the storage
            ContentResolver resolver = c.getContentResolver();
            Uri made = null;
            try {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, file);
                v.put(MediaStore.MediaColumns.MIME_TYPE, kind);
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, folderName + "/" + FOLDER);
                v.put(MediaStore.Images.Media.IS_PENDING, 1);
                Uri collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                made = resolver.insert(collection, v);
                if (made == null) throw new java.io.IOException("the gallery gave no place for it");
                try (OutputStream out = resolver.openOutputStream(made)) {
                    if (out == null) throw new java.io.IOException("the gallery's file cannot be written");
                    out.write(data);
                }
                v.clear();
                v.put(MediaStore.Images.Media.IS_PENDING, 0);
                resolver.update(made, v, null, null);
                return new String[]{place, made.toString()};
            } catch (Exception e) {
                first = e;
                if (made != null) {
                    try {
                        resolver.delete(made, null, null);
                    } catch (Exception ignored) {
                        // nothing was kept
                    }
                }
            }
        }
        if (!Ubuntu.hasStorageAccess(c)) {
            if (first != null) {
                android.util.Log.w("Termant", "gallery: cannot add the picture", first);
                throw new PhoneBridge.Refused("phone gallery: the phone did not take the picture (" + first.getMessage() + ")");
            }
            throw new PhoneBridge.Refused("phone gallery: Termant may not write to the phone's pictures"
                    + " yet. In Termant open the menu and choose \"Allow phone"
                    + " storage (/sdcard)\", then try again.");
        }
        try {
            File folder = new File(Environment.getExternalStoragePublicDirectory(folderName), FOLDER);
            if (!folder.isDirectory() && !folder.mkdirs()) throw new java.io.IOException("cannot make " + folder);
            File target = new File(folder, file);
            for (int i = 2; target.exists() && i < 1000; i++) target = new File(folder, base + "-" + i + "." + ending);
            try (FileOutputStream out = new FileOutputStream(target)) {
                out.write(data);
            }
            // the gallery learns of the file; its address comes back when the phone has looked at it
            final String[] address = {""};
            final CountDownLatch looked = new CountDownLatch(1);
            MediaScannerConnection.scanFile(c.getApplicationContext(), new String[]{target.getPath()}, new String[]{kind},
                    new MediaScannerConnection.OnScanCompletedListener() {
                        @Override
                        public void onScanCompleted(String path, Uri uri) {
                            if (uri != null) address[0] = uri.toString();
                            looked.countDown();
                        }
                    });
            try {
                looked.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            return new String[]{folderName + "/" + FOLDER + "/" + target.getName(), address[0]};
        } catch (Exception e) {
            throw new PhoneBridge.Refused("phone gallery: the picture could not be saved (" + e.getMessage() + ")");
        }
    }
}
