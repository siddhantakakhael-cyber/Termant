package com.termant.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/**
 * Downloads the official Ubuntu base image from Canonical, verifies its
 * checksum, unpacks it and prepares it for use on Android. Runs once.
 */
final class Installer {
    interface Listener {
        /** percent is 0..100, or -1 when the amount of work is unknown. */
        void onInstallProgress(String status, int percent);

        void onInstallDone();

        void onInstallError(String message);
    }

    private static final String BASE_URL =
            "https://cdimage.ubuntu.com/ubuntu-base/releases/" + Ubuntu.RELEASE + "/release/";

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static Listener listener;
    private static boolean running;
    private static String lastStatus;
    private static int lastPercent = -1;

    private Installer() {
    }

    static synchronized boolean isRunning() {
        return running;
    }

    /** Attaches the screen to an install in progress, or starts one. */
    static synchronized void start(Context context, Listener l) {
        listener = l;
        if (running) {
            if (lastStatus != null) l.onInstallProgress(lastStatus, lastPercent);
            return;
        }
        running = true;
        lastStatus = null;
        final Context app = context.getApplicationContext();
        new Thread("termant-install") {
            @Override
            public void run() {
                String error = null;
                try {
                    install(app);
                } catch (Throwable t) {
                    android.util.Log.e("Termant", "Ubuntu setup failed", t);
                    error = describe(t);
                }
                final String result = error;
                MAIN.post(new Runnable() {
                    @Override
                    public void run() {
                        Listener target;
                        synchronized (Installer.class) {
                            running = false;
                            target = listener;
                        }
                        if (target == null) return;
                        if (result == null) target.onInstallDone();
                        else target.onInstallError(result);
                    }
                });
            }
        }.start();
    }

    static synchronized void detach(Listener l) {
        if (listener == l) listener = null;
    }

    private static String describe(Throwable t) {
        if (t instanceof java.net.UnknownHostException || t instanceof java.net.ConnectException) {
            return "No internet connection. Connect and try again.";
        }
        if (t instanceof java.net.SocketTimeoutException) {
            return "The connection is too slow or was interrupted. Try again.";
        }
        String m = t.getMessage();
        return (m == null || m.isEmpty()) ? t.toString() : m;
    }

    private static void progress(final String status, final int percent) {
        MAIN.post(new Runnable() {
            @Override
            public void run() {
                Listener target;
                synchronized (Installer.class) {
                    lastStatus = status;
                    lastPercent = percent;
                    target = listener;
                }
                if (target != null) target.onInstallProgress(status, percent);
            }
        });
    }

    // ------------------------------------------------------------ steps

    private static void install(Context c) throws Exception {
        String arch = Ubuntu.arch(c);
        if (arch == null) {
            throw new IOException(c.getString(R.string.unsupported_device, Ubuntu.abiName(c)));
        }
        if (!Ubuntu.proot(c).isFile()) {
            throw new IOException("The app is not installed correctly (PRoot is missing). Reinstall the APK.");
        }
        Ubuntu.prepareHost(c);

        File root = Ubuntu.rootfs(c);
        Ubuntu.marker(c).delete();
        deleteTree(root);

        progress(c.getString(R.string.step_find), -1);
        String[] image = findImage(arch); // {file name, sha256}

        File tarball = new File(c.getCacheDir(), "ubuntu-base.tar.gz");
        download(c, BASE_URL + image[0], tarball, image[1]);

        progress(c.getString(R.string.step_extract), 0);
        if (!root.mkdirs()) throw new IOException("Cannot create " + root);
        final long total = tarball.length();
        final String status = c.getString(R.string.step_extract);
        try (CountingStream counted = new CountingStream(new FileInputStream(tarball)) {
            int shown = 0;

            @Override
            void moved() {
                int pct = (int) (count * 100 / Math.max(1, total));
                if (pct != shown) {
                    shown = pct;
                    progress(status, pct);
                }
            }
        }; InputStream in = new GZIPInputStream(new BufferedInputStream(counted, 64 * 1024), 64 * 1024)) {
            new TarExtractor(root).extract(in);
        }
        tarball.delete();

        progress(c.getString(R.string.step_configure), -1);
        configure(c, root);
        runSetup(c);

        if (!Ubuntu.marker(c).createNewFile()) throw new IOException("Cannot finish setup");
    }

    /** Picks the newest point release listed by the server; falls back to known names. */
    private static String[] findImage(String arch) throws IOException {
        String sums = new String(fetch(BASE_URL + "SHA256SUMS", 1024 * 1024), StandardCharsets.UTF_8);
        Pattern p = Pattern.compile("(?m)^([0-9a-f]{64})\\s+\\*?(ubuntu-base-"
                + Pattern.quote(Ubuntu.RELEASE) + "(?:\\.(\\d+))?-base-" + arch + "\\.tar\\.gz)\\s*$");
        Matcher m = p.matcher(sums);
        String name = null, sha = null;
        int best = -1;
        while (m.find()) {
            int point = m.group(3) == null ? 0 : Integer.parseInt(m.group(3));
            if (point > best) {
                best = point;
                sha = m.group(1);
                name = m.group(2);
            }
        }
        if (name == null) throw new IOException("Ubuntu " + Ubuntu.RELEASE + " has no image for " + arch);
        return new String[]{name, sha};
    }

    private static HttpURLConnection open(String url) throws IOException {
        HttpURLConnection conn = (HttpURLConnection) new URL(url).openConnection();
        conn.setConnectTimeout(20000);
        conn.setReadTimeout(30000);
        conn.setRequestProperty("User-Agent", "Termant");
        int code = conn.getResponseCode();
        if (code != 200) {
            conn.disconnect();
            throw new IOException("Ubuntu's server answered " + code + " for " + url);
        }
        return conn;
    }

    private static byte[] fetch(String url, int limit) throws IOException {
        HttpURLConnection conn = open(url);
        try (InputStream in = conn.getInputStream()) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                if (out.size() > limit) throw new IOException("Unexpected answer from " + url);
            }
            return out.toByteArray();
        } finally {
            conn.disconnect();
        }
    }

    private static void download(Context c, String url, File to, String sha256) throws Exception {
        HttpURLConnection conn = open(url);
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        long total = conn.getContentLength();
        try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(to)) {
            byte[] buf = new byte[64 * 1024];
            long done = 0;
            int n, shown = -1;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                digest.update(buf, 0, n);
                done += n;
                int mb = (int) (done >> 20);
                if (mb != shown) {
                    shown = mb;
                    int totalMb = (int) Math.max(1, (total + (1 << 20) - 1) >> 20);
                    progress(c.getString(R.string.step_download, mb, totalMb),
                            total > 0 ? (int) (done * 100 / total) : -1);
                }
            }
        } finally {
            conn.disconnect();
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : digest.digest()) hex.append(String.format("%02x", b));
        if (!hex.toString().equals(sha256)) {
            to.delete();
            throw new IOException("The download was damaged on the way (checksum mismatch). Try again.");
        }
    }

    private static void write(File f, String text) throws IOException {
        File dir = f.getParentFile();
        if (dir != null) dir.mkdirs();
        // Replace rather than follow: the image may ship a dangling symlink here.
        f.delete();
        try (OutputStream out = new FileOutputStream(f)) {
            out.write(text.getBytes(StandardCharsets.UTF_8));
        }
    }

    private static void configure(Context c, File root) throws IOException {
        // Android has no /etc/resolv.conf, so name lookups need explicit servers.
        write(new File(root, "etc/resolv.conf"), "nameserver 8.8.8.8\nnameserver 1.1.1.1\n");
        write(new File(root, "etc/hosts"),
                "127.0.0.1 localhost\n::1 localhost ip6-localhost ip6-loopback\n");
        // apt cannot switch to its unprivileged "_apt" user without real root.
        write(new File(root, "etc/apt/apt.conf.d/99termant"),
                "APT::Sandbox::User \"root\";\n");
        write(new File(root, "etc/profile.d/termant.sh"),
                "if [ ! -e \"$HOME/.termant-welcome\" ]; then\n"
                        + "  touch \"$HOME/.termant-welcome\"\n"
                        + "  printf '\\nWelcome to Ubuntu on Termant.\\nInstall software with:\\n  apt update\\n  apt install <package>\\n\\n'\n"
                        + "fi\n");
        write(new File(root, "usr/local/lib/termant/setup.sh"),
                "#!/bin/sh\n"
                        + "# Give Android's numeric groups a name so tools do not complain.\n"
                        + "for g in $(id -G 2>/dev/null); do\n"
                        + "  grep -q \"^[^:]*:[^:]*:$g:\" /etc/group || echo \"android_$g:x:$g:\" >> /etc/group\n"
                        + "done\n"
                        + "echo termant-setup-ok\n");
        new File(root, "tmp").mkdirs();
        new File(root, "root").mkdirs();
        new File(root, "sdcard").mkdirs();
        new File(root, "storage").mkdirs();

        File proc = Ubuntu.fakeProc(c);
        write(new File(proc, "loadavg"), "0.12 0.07 0.02 2/165 765\n");
        write(new File(proc, "uptime"), "124.08 932.80\n");
        write(new File(proc, "version"),
                "Linux version 6.8.0-termant (termant@android) (gcc) #1 SMP PREEMPT\n");
        write(new File(proc, "stat"),
                "cpu  1957 0 2877 93280 262 342 254 87 0 0\n"
                        + "cpu0 1957 0 2877 93280 262 342 254 87 0 0\n"
                        + "intr 1\nctxt 140223\nbtime 1680020856\nprocesses 772\n"
                        + "procs_running 2\nprocs_blocked 0\nsoftirq 1 0 0 0 0 0 0 0 0 0 0\n");
        write(new File(proc, "vmstat"), "nr_free_pages 1\npgpgin 0\npgpgout 0\npswpin 0\npswpout 0\n");
    }

    /** First real start of Ubuntu: proves PRoot works on this device before saying "done". */
    private static void runSetup(Context c) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(
                Ubuntu.command(c, "/bin/sh", "/usr/local/lib/termant/setup.sh"));
        pb.environment().clear();
        for (String kv : Ubuntu.hostEnvironment(c)) {
            int eq = kv.indexOf('=');
            pb.environment().put(kv.substring(0, eq), kv.substring(eq + 1));
        }
        pb.redirectErrorStream(true);
        pb.directory(c.getFilesDir());
        Process process = pb.start();
        process.getOutputStream().close();
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (InputStream in = process.getInputStream()) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                if (out.size() < 16000) out.write(buf, 0, n);
            }
        }
        int code = process.waitFor();
        String text = new String(out.toByteArray(), StandardCharsets.UTF_8).trim();
        if (!text.contains("termant-setup-ok")) {
            throw new IOException("Ubuntu could not start on this device (exit " + code + ").\n" + text);
        }
    }

    // ------------------------------------------------------------ helpers

    /** Deletes a directory tree without ever following symlinks out of it. */
    static void deleteTree(File f) {
        try {
            if (OsConstants.S_ISDIR(Os.lstat(f.getPath()).st_mode)) {
                try {
                    Os.chmod(f.getPath(), 0700);
                } catch (ErrnoException e) {
                    // try anyway
                }
                String[] names = f.list();
                if (names != null) {
                    for (String name : names) deleteTree(new File(f, name));
                }
            }
        } catch (ErrnoException e) {
            return; // does not exist
        }
        f.delete();
    }

    private abstract static class CountingStream extends FilterInputStream {
        long count;

        CountingStream(InputStream in) {
            super(in);
        }

        abstract void moved();

        @Override
        public int read() throws IOException {
            int b = super.read();
            if (b >= 0) {
                count++;
                moved();
            }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            int n = super.read(b, off, len);
            if (n > 0) {
                count += n;
                moved();
            }
            return n;
        }
    }
}
