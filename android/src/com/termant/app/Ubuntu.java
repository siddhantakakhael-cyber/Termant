package com.termant.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.system.Os;

import java.io.File;
import java.io.FileInputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Where the Ubuntu system lives and how to start programs inside it with PRoot. */
final class Ubuntu {
    static final String RELEASE = "24.04";

    private Ubuntu() {
    }

    static File rootfs(Context c) {
        return new File(c.getFilesDir(), "rootfs");
    }

    /** Written last, so a half-finished install is never mistaken for a working one. */
    static File marker(Context c) {
        return new File(c.getFilesDir(), "installed");
    }

    static boolean isInstalled(Context c) {
        return marker(c).isFile();
    }

    static File fakeProc(Context c) {
        return new File(c.getFilesDir(), "proc");
    }

    private static File nativeDir(Context c) {
        return new File(c.getApplicationInfo().nativeLibraryDir);
    }

    static File proot(Context c) {
        return new File(nativeDir(c), "libproot.so");
    }

    /**
     * Ubuntu architecture matching the native libraries Android installed for
     * this app (the directory is named arm64, arm or x86_64), or null.
     */
    static String arch(Context c) {
        String abi = nativeDir(c).getName();
        if (abi.equals("arm64")) return "arm64";
        if (abi.equals("arm")) return "armhf";
        if (abi.equals("x86_64")) return "amd64";
        return null;
    }

    static String abiName(Context c) {
        return nativeDir(c).getName();
    }

    /**
     * PRoot needs libtalloc.so.2, but Android only installs libraries named
     * lib*.so. A link with the expected name is kept in app data and refreshed
     * on every start, because the install path changes when the app updates.
     */
    static void prepareHost(Context c) throws Exception {
        File lib = new File(c.getFilesDir(), "lib");
        lib.mkdirs();
        new File(c.getFilesDir(), "tmp").mkdirs();
        File link = new File(lib, "libtalloc.so.2");
        String target = new File(nativeDir(c), "libtalloc.so").getPath();
        try {
            if (target.equals(Os.readlink(link.getPath()))) return;
        } catch (Exception e) {
            // missing or not a link
        }
        link.delete();
        Os.symlink(target, link.getPath());
    }

    /** Environment of the PRoot process itself (not of the programs inside Ubuntu). */
    static String[] hostEnvironment(Context c) {
        List<String> env = new ArrayList<>();
        File dir = nativeDir(c);
        env.add("PROOT_LOADER=" + new File(dir, "libproot-loader.so").getPath());
        File loader32 = new File(dir, "libproot-loader32.so");
        if (loader32.exists()) env.add("PROOT_LOADER_32=" + loader32.getPath());
        env.add("PROOT_TMP_DIR=" + new File(c.getFilesDir(), "tmp").getPath());
        env.add("LD_LIBRARY_PATH=" + new File(c.getFilesDir(), "lib").getPath());
        env.add("HOME=" + c.getFilesDir().getPath());
        env.add("PATH=/system/bin");
        return env.toArray(new String[0]);
    }

    static boolean hasStorageAccess(Context c) {
        return c.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                == PackageManager.PERMISSION_GRANTED;
    }

    private static boolean readable(String path) {
        try (FileInputStream in = new FileInputStream(path)) {
            return in.read() >= 0;
        } catch (Exception e) {
            return false;
        }
    }

    /** Full argv (argv[0] included) that runs {@code guest} as root inside Ubuntu. */
    static String[] command(Context c, String... guest) {
        String root = rootfs(c).getPath();
        List<String> a = new ArrayList<>();
        a.add(proot(c).getPath());
        a.add("--kill-on-exit");
        a.add("--link2symlink"); // Android forbids hard links; dpkg needs them
        a.add("--sysvipc");
        a.add("-L");
        a.add("--kernel-release=6.8.0-termant");
        a.add("--root-id");
        a.add("--rootfs=" + root);
        a.add("--cwd=/root");
        a.add("--bind=/dev");
        a.add("--bind=/dev/urandom:/dev/random");
        a.add("--bind=/proc");
        a.add("--bind=/proc/self/fd:/dev/fd");
        a.add("--bind=/proc/self/fd/0:/dev/stdin");
        a.add("--bind=/proc/self/fd/1:/dev/stdout");
        a.add("--bind=/proc/self/fd/2:/dev/stderr");
        a.add("--bind=/sys");
        a.add("--bind=" + root + "/tmp:/dev/shm");

        // Android hides some /proc files from apps; tools like uptime and top
        // need them to exist, so harmless stand-ins are shown instead.
        File fake = fakeProc(c);
        for (String name : new String[]{"loadavg", "stat", "uptime", "version", "vmstat"}) {
            File f = new File(fake, name);
            if (f.isFile() && !readable("/proc/" + name)) {
                a.add("--bind=" + f.getPath() + ":/proc/" + name);
            }
        }

        if (hasStorageAccess(c)) {
            File sd = Environment.getExternalStorageDirectory();
            if (sd != null && sd.isDirectory()) {
                a.add("--bind=" + sd.getPath() + ":/sdcard");
                a.add("--bind=/storage");
            }
        }

        a.add("/usr/bin/env");
        a.add("-i");
        a.add("HOME=/root");
        a.add("USER=root");
        a.add("LOGNAME=root");
        a.add("SHELL=/bin/bash");
        a.add("TERM=xterm-256color");
        a.add("COLORTERM=truecolor");
        a.add("LANG=C.UTF-8");
        a.add("TMPDIR=/tmp");
        a.add("PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin");
        Collections.addAll(a, guest);
        return a.toArray(new String[0]);
    }

    static String[] shellCommand(Context c) {
        return command(c, "/bin/bash", "-l");
    }
}
