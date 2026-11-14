package com.termant.app;

import android.system.ErrnoException;
import android.system.Os;
import android.system.OsConstants;

import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/**
 * Unpacks a root filesystem tarball into app storage.
 *
 * Handles what Ubuntu's base image uses: files, directories, symlinks, hard
 * links, GNU long names and POSIX pax headers. Ownership is ignored (PRoot
 * presents everything as root) and device nodes are skipped (/dev comes from
 * Android). Hard links become copies because Android does not allow apps to
 * create them.
 */
final class TarExtractor {
    private final File root;
    private final String rootPath;
    private final byte[] buf = new byte[64 * 1024];

    TarExtractor(File root) throws IOException {
        this.root = root;
        this.rootPath = root.getCanonicalPath();
    }

    void extract(InputStream in) throws IOException {
        byte[] h = new byte[512];
        String longName = null, longLink = null, paxPath = null, paxLink = null;
        long paxSize = -1;

        while (true) {
            if (!readBlock(in, h)) break;
            if (isZero(h)) break;

            long size = number(h, 124, 12);
            int mode = (int) number(h, 100, 8);
            char type = (char) (h[156] & 0xff);
            String name = text(h, 0, 100);
            String link = text(h, 157, 100);
            // Only POSIX ustar has a name prefix; GNU tar keeps other data there.
            boolean posix = h[257] == 'u' && h[258] == 's' && h[259] == 't' && h[260] == 'a'
                    && h[261] == 'r' && h[262] == 0;
            if (posix) {
                String prefix = text(h, 345, 155);
                if (!prefix.isEmpty()) name = prefix + "/" + name;
            }

            if (type == 'L' || type == 'K') {
                String value = trimNul(new String(readData(in, size), StandardCharsets.UTF_8));
                if (type == 'L') longName = value;
                else longLink = value;
                continue;
            }
            if (type == 'x') {
                byte[] data = readData(in, size);
                int pos = 0;
                while (pos < data.length) {
                    int sp = pos;
                    while (sp < data.length && data[sp] != ' ') sp++;
                    if (sp >= data.length) break;
                    int len;
                    try {
                        len = Integer.parseInt(new String(data, pos, sp - pos, StandardCharsets.US_ASCII));
                    } catch (NumberFormatException e) {
                        break;
                    }
                    if (len <= 0 || pos + len > data.length) break;
                    String rec = new String(data, sp + 1, len - (sp - pos) - 2, StandardCharsets.UTF_8);
                    int eq = rec.indexOf('=');
                    if (eq > 0) {
                        String key = rec.substring(0, eq), value = rec.substring(eq + 1);
                        if (key.equals("path")) paxPath = value;
                        else if (key.equals("linkpath")) paxLink = value;
                        else if (key.equals("size")) paxSize = Long.parseLong(value);
                    }
                    pos += len;
                }
                continue;
            }
            if (type == 'g') {
                skip(in, padded(size));
                continue;
            }

            if (paxPath != null) name = paxPath;
            else if (longName != null) name = longName;
            if (paxLink != null) link = paxLink;
            else if (longLink != null) link = longLink;
            if (paxSize >= 0) size = paxSize;
            longName = longLink = paxPath = paxLink = null;
            paxSize = -1;

            File target = resolve(name);
            if (target == null) {
                if (type == '0' || type == 0 || type == '7') skip(in, padded(size));
                continue;
            }

            switch (type) {
                case '5':
                    makeDir(target);
                    chmod(target, (mode & 0777) | 0700);
                    break;
                case '2':
                    makeDir(target.getParentFile());
                    remove(target);
                    symlink(link, target);
                    break;
                case '1': {
                    File source = resolve(link);
                    makeDir(target.getParentFile());
                    remove(target);
                    if (source != null) copy(source, target);
                    break;
                }
                case '0':
                case 0:
                case '7':
                    makeDir(target.getParentFile());
                    remove(target);
                    try (OutputStream out = new FileOutputStream(target)) {
                        long left = size;
                        while (left > 0) {
                            int n = in.read(buf, 0, (int) Math.min(buf.length, left));
                            if (n < 0) throw new EOFException("Archive ends early");
                            out.write(buf, 0, n);
                            left -= n;
                        }
                    }
                    skip(in, padded(size) - size);
                    chmod(target, (mode & 0777) | 0600);
                    break;
                default:
                    // character/block devices and fifos: nothing to create
                    break;
            }
        }
    }

    /** Maps an archive path to a file below the root; null for "." or anything escaping it. */
    private File resolve(String name) {
        File f = root;
        boolean any = false;
        for (String part : name.split("/")) {
            if (part.isEmpty() || part.equals(".")) continue;
            if (part.equals("..")) return null;
            f = new File(f, part);
            any = true;
        }
        return any ? f : null;
    }

    private void makeDir(File dir) throws IOException {
        if (dir.isDirectory()) return;
        // A parent may exist as a symlink to a directory (/bin -> usr/bin): fine.
        if (!dir.mkdirs() && !dir.isDirectory()) throw new IOException("Cannot create " + dir);
        if (!dir.getCanonicalPath().startsWith(rootPath)) throw new IOException("Path leaves root: " + dir);
    }

    private static boolean isLink(File f) {
        try {
            return OsConstants.S_ISLNK(Os.lstat(f.getPath()).st_mode);
        } catch (ErrnoException e) {
            return false;
        }
    }

    private static void remove(File f) {
        if (isLink(f) || f.isFile()) f.delete();
    }

    private static void symlink(String to, File at) throws IOException {
        try {
            Os.symlink(to, at.getPath());
        } catch (ErrnoException e) {
            throw new IOException("symlink " + at + ": " + e.getMessage());
        }
    }

    private static void chmod(File f, int mode) {
        try {
            Os.chmod(f.getPath(), mode);
        } catch (ErrnoException e) {
            // keep default permissions
        }
    }

    private void copy(File from, File to) throws IOException {
        try {
            if (isLink(from)) {
                symlink(Os.readlink(from.getPath()), to);
                return;
            }
            if (!from.isFile()) return;
            try (InputStream in = new FileInputStream(from); OutputStream out = new FileOutputStream(to)) {
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            }
            chmod(to, Os.stat(from.getPath()).st_mode & 0777);
        } catch (ErrnoException e) {
            throw new IOException("link " + to + ": " + e.getMessage());
        }
    }

    // ------------------------------------------------------------ tar format

    private static long padded(long size) {
        return (size + 511) / 512 * 512;
    }

    private static boolean readBlock(InputStream in, byte[] b) throws IOException {
        int off = 0;
        while (off < b.length) {
            int n = in.read(b, off, b.length - off);
            if (n < 0) {
                if (off == 0) return false;
                throw new EOFException("Archive ends early");
            }
            off += n;
        }
        return true;
    }

    private static byte[] readData(InputStream in, long size) throws IOException {
        if (size < 0 || size > 1024 * 1024) throw new IOException("Bad header size");
        byte[] data = new byte[(int) size];
        int off = 0;
        while (off < data.length) {
            int n = in.read(data, off, data.length - off);
            if (n < 0) throw new EOFException("Archive ends early");
            off += n;
        }
        skip(in, padded(size) - size);
        return data;
    }

    private static void skip(InputStream in, long count) throws IOException {
        byte[] tmp = new byte[8192];
        while (count > 0) {
            int n = in.read(tmp, 0, (int) Math.min(tmp.length, count));
            if (n < 0) throw new EOFException("Archive ends early");
            count -= n;
        }
    }

    private static boolean isZero(byte[] b) {
        for (byte x : b) if (x != 0) return false;
        return true;
    }

    private static String trimNul(String s) {
        int i = s.indexOf('\0');
        return i < 0 ? s : s.substring(0, i);
    }

    private static String text(byte[] b, int off, int len) {
        int end = off;
        while (end < off + len && b[end] != 0) end++;
        return new String(b, off, end - off, StandardCharsets.UTF_8);
    }

    private static long number(byte[] b, int off, int len) {
        if ((b[off] & 0x80) != 0) { // GNU base-256 for large values
            long v = b[off] & 0x7f;
            for (int i = 1; i < len; i++) v = (v << 8) | (b[off + i] & 0xff);
            return v;
        }
        long v = 0;
        for (int i = off; i < off + len; i++) {
            int c = b[i];
            if (c >= '0' && c <= '7') v = v * 8 + (c - '0');
            else if (c == 0 || (c == ' ' && v != 0)) break;
        }
        return v;
    }
}
