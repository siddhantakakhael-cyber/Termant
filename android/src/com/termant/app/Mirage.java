package com.termant.app;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Rect;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/**
 * Reads the writing in a picture of the screen, on the phone itself, for apps
 * that do not tell Android what they show (games, drawings, pictures).
 *
 * The reading is done by Mirage, a small vision model by Cactus Compute
 * (Apache 2.0). Its engine is a small program inside the app (libmirage.so,
 * built from android/mirage/reader.c); the model (22 MB) is downloaded on
 * first use from a fixed revision and checked against its SHA-256. The
 * picture goes from memory to that program and nowhere else.
 *
 * It reads Latin letters only, and letters can be wrong; the places are right.
 */
final class Mirage {
    private static final String MODEL_URL =
            "https://huggingface.co/Cactus-Compute/mirage/resolve/76c1e9e53381aca8fc3843c636216c96ff220122/mirage.cact";
    private static final String MODEL_SHA256 = "4249a0f7515272ff76b9414bec17d7e7daf84b15b66c7fb4f17fce1e66f9f237";
    private static final long MODEL_BYTES = 21611043L;
    /** A part is made this small at most before it is read: the model looks at a small picture anyway. */
    private static final int LONG_SIDE = 640;
    private static final long TIME_LIMIT_MS = 120000;

    /** One piece of writing and where it is on the screen. */
    static final class Piece {
        final String text;
        final double score;
        final Rect box;
        /** False when the piece touches the cut between two parts: it is probably cut off there. */
        final boolean whole;

        Piece(String text, double score, Rect box, boolean whole) {
            this.text = text;
            this.score = score;
            this.box = box;
            this.whole = whole;
        }
    }

    /** What a reading gave. */
    static final class Reading {
        final List<Piece> pieces = new ArrayList<>();
        int parts;
        long ms;
    }

    private Mirage() {
    }

    private static File program(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir, "libmirage.so");
    }

    /** The model file, downloaded first if it is not there yet. */
    private static synchronized File model(Context c) throws IOException {
        File dir = new File(c.getFilesDir(), "mirage");
        File file = new File(dir, "mirage.cact");
        if (file.length() == MODEL_BYTES) return file;
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("its folder cannot be made");
        File part = new File(dir, "mirage.cact.part");
        android.util.Log.i("Termant", "mirage: downloading the model");
        HttpURLConnection conn = null;
        try {
            conn = (HttpURLConnection) new URL(MODEL_URL).openConnection();
            conn.setConnectTimeout(20000);
            conn.setReadTimeout(30000);
            conn.setRequestProperty("User-Agent", "Termant");
            int code = conn.getResponseCode();
            if (code != 200) throw new IOException("the server answered " + code);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(part)) {
                byte[] buf = new byte[64 * 1024];
                long done = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    done += n;
                    if (done > MODEL_BYTES) throw new IOException("the file is not the expected one");
                    out.write(buf, 0, n);
                    digest.update(buf, 0, n);
                }
            }
            StringBuilder hex = new StringBuilder();
            for (byte b : digest.digest()) hex.append(String.format(Locale.ROOT, "%02x", b));
            if (!MODEL_SHA256.contentEquals(hex)) throw new IOException("the file is not the expected one");
            if (!part.renameTo(file)) throw new IOException("the file cannot be saved");
            android.util.Log.i("Termant", "mirage: model ready");
            return file;
        } catch (IOException e) {
            part.delete();
            throw new IOException("the reader's model (22 MB) could not be downloaded, it needs the internet once: "
                    + e.getMessage());
        } catch (Exception e) {
            part.delete();
            throw new IOException("the reader's model could not be checked: " + e.getMessage());
        } finally {
            if (conn != null) conn.disconnect();
        }
    }

    /** A tall screen is read as its upper and its lower part, a wide one as left and right: small writing reads better so. */
    private static List<Rect> parts(int w, int h) {
        List<Rect> parts = new ArrayList<>();
        if (h >= w * 1.3) {
            int ph = Math.round(h * 0.5625f);
            parts.add(new Rect(0, 0, w, ph));
            parts.add(new Rect(0, h - ph, w, h));
        } else if (w >= h * 1.3) {
            int pw = Math.round(w * 0.5625f);
            parts.add(new Rect(0, 0, pw, h));
            parts.add(new Rect(w - pw, 0, w, h));
        } else {
            parts.add(new Rect(0, 0, w, h));
        }
        return parts;
    }

    /** The writing in this picture of the whole screen. */
    static Reading read(Context c, Bitmap screen) throws IOException {
        File program = program(c);
        if (!program.isFile()) throw new IOException("this phone's processor is not supported");
        File model = model(c);
        long start = android.os.SystemClock.elapsedRealtime();
        Reading reading = new Reading();
        List<Piece> found = new ArrayList<>();
        final Process process;
        try {
            process = new ProcessBuilder(program.getPath(), model.getPath()).start();
        } catch (IOException e) {
            throw new IOException("this phone cannot run the reader (a 32-bit system, probably)");
        }
        Thread guard = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Thread.sleep(TIME_LIMIT_MS);
                    process.destroy();
                } catch (InterruptedException e) {
                    // finished in time
                }
            }
        }, "mirage-guard");
        guard.setDaemon(true);
        guard.start();
        try {
            OutputStream to = new BufferedOutputStream(process.getOutputStream(), 1 << 16);
            BufferedReader from = new BufferedReader(new InputStreamReader(process.getInputStream(), "UTF-8"));
            for (Rect part : parts(screen.getWidth(), screen.getHeight())) {
                float scale = Math.min(1f, LONG_SIDE / (float) Math.max(part.width(), part.height()));
                int w = Math.max(8, Math.round(part.width() * scale)), h = Math.max(8, Math.round(part.height() * scale));
                Bitmap cut = Bitmap.createBitmap(screen, part.left, part.top, part.width(), part.height());
                Bitmap small = cut.getWidth() == w && cut.getHeight() == h ? cut : Bitmap.createScaledBitmap(cut, w, h, true);
                int[] pixels = new int[w * h];
                small.getPixels(pixels, 0, w, 0, 0, w, h);
                if (small != cut) small.recycle();
                if (cut != screen) cut.recycle();
                if (plain(pixels)) continue;
                byte[] rgb = new byte[w * h * 3];
                for (int i = 0, o = 0; i < pixels.length; i++) {
                    int p = pixels[i];
                    rgb[o++] = (byte) (p >> 16);
                    rgb[o++] = (byte) (p >> 8);
                    rgb[o++] = (byte) p;
                }
                to.write((w + " " + h + "\n").getBytes("US-ASCII"));
                to.write(rgb);
                to.flush();
                String line = from.readLine();
                if (line == null) throw new IOException(whyStopped(process));
                reading.parts++;
                JSONArray texts = new JSONObject(line).optJSONArray("text");
                for (int i = 0; texts != null && i < texts.length(); i++) {
                    JSONObject t = texts.getJSONObject(i);
                    String text = t.optString("string").trim();
                    double score = t.optDouble("score", 0);
                    JSONArray b = t.optJSONArray("bbox"); // left, top, right, bottom, each 0..1000 of the part
                    if (text.isEmpty() || b == null || b.length() < 4) continue;
                    if (text.length() < 2 && score < 0.5) continue; // mostly icons taken for a letter
                    Rect box = new Rect(part.left + (int) (b.getDouble(0) * part.width() / 1000),
                            part.top + (int) (b.getDouble(1) * part.height() / 1000),
                            part.left + (int) (b.getDouble(2) * part.width() / 1000),
                            part.top + (int) (b.getDouble(3) * part.height() / 1000));
                    if (box.width() < 2 || box.height() < 2) continue;
                    boolean cutOff = (b.getDouble(0) < 8 && part.left > 0) || (b.getDouble(1) < 8 && part.top > 0)
                            || (b.getDouble(2) > 992 && part.right < screen.getWidth())
                            || (b.getDouble(3) > 992 && part.bottom < screen.getHeight());
                    found.add(new Piece(text, score, box, !cutOff));
                }
            }
            to.close();
        } catch (org.json.JSONException e) {
            throw new IOException("the reader gave an answer that cannot be understood");
        } finally {
            guard.interrupt();
            process.destroy();
        }
        reading.pieces.addAll(tidy(found, screen.getHeight()));
        reading.ms = android.os.SystemClock.elapsedRealtime() - start;
        android.util.Log.i("Termant", "mirage: " + reading.pieces.size() + " pieces from " + reading.parts
                + " parts in " + reading.ms + " ms");
        return reading;
    }

    /** A part that is one flat colour has nothing to read. */
    private static boolean plain(int[] pixels) {
        int first = pixels[0], different = 0;
        for (int i = 0; i < pixels.length && different < 12; i += 7) {
            int p = pixels[i];
            int d = Math.abs(((p >> 16) & 255) - ((first >> 16) & 255)) + Math.abs(((p >> 8) & 255) - ((first >> 8) & 255))
                    + Math.abs((p & 255) - (first & 255));
            if (d > 40) different++;
        }
        return different < 12;
    }

    private static String whyStopped(Process process) {
        String said = "";
        try {
            int code = process.waitFor();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            InputStream err = process.getErrorStream();
            byte[] buf = new byte[1024];
            int n;
            while (out.size() < 400 && (n = err.read(buf)) > 0) out.write(buf, 0, n);
            said = out.toString("UTF-8").trim();
            if (said.isEmpty()) said = "it stopped with code " + code;
        } catch (Exception e) {
            said = "it stopped";
        }
        return "the reader failed: " + said;
    }

    /**
     * The two parts overlap, so some writing was read twice, and the model
     * itself sometimes gives a line both whole and in pieces: of two boxes on
     * the same spot the whole and surer one stays. Then top to bottom, left to right.
     */
    private static List<Piece> tidy(List<Piece> found, int screenHeight) {
        Collections.sort(found, new Comparator<Piece>() {
            @Override
            public int compare(Piece a, Piece b) {
                if (a.whole != b.whole) return a.whole ? -1 : 1;
                return Double.compare(b.score, a.score);
            }
        });
        List<Piece> kept = new ArrayList<>();
        for (Piece p : found) {
            boolean twice = false;
            for (Piece k : kept) {
                Rect both = new Rect();
                if (!both.setIntersect(p.box, k.box)) continue;
                long smaller = Math.min((long) p.box.width() * p.box.height(), (long) k.box.width() * k.box.height());
                if ((long) both.width() * both.height() * 2 > smaller) {
                    twice = true;
                    break;
                }
            }
            if (!twice) kept.add(p);
        }
        final int row = Math.max(8, screenHeight / 64);
        Collections.sort(kept, new Comparator<Piece>() {
            @Override
            public int compare(Piece a, Piece b) {
                int ra = a.box.centerY() / row, rb = b.box.centerY() / row;
                return ra != rb ? Integer.compare(ra, rb) : Integer.compare(a.box.left, b.box.left);
            }
        });
        return kept;
    }
}
