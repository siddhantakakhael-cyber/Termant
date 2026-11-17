package com.termant.app;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What the agent keeps across chats: notes about the user and the phone, and
 * tools it made for itself. The agent changes both with the "learn" command
 * inside Ubuntu (assets/guest/learn); this class lets Settings show them and
 * take one away. The file the agent reads is written again by "learn refresh"
 * before each chat, so nothing has to be rebuilt here.
 */
final class Learned {
    /** Must match assets/guest/learn. */
    private static final String DIR = "root/.config/termant/learned";
    private static final String TOOLS = "usr/local/bin";
    private static final String MARK = "# termant-tool:";

    private Learned() {
    }

    private static File dir(Context c) {
        return new File(Ubuntu.rootfs(c), DIR);
    }

    private static File notesFile(Context c) {
        return new File(dir(c), "notes.txt");
    }

    /** Whether the agent may keep notes and tools, and is told what it has. */
    static boolean on(Context c) {
        return !new File(dir(c), "off").exists();
    }

    static void setOn(Context c, boolean on) throws IOException {
        File off = new File(dir(c), "off");
        if (on) {
            if (off.exists() && !off.delete()) throw new IOException("cannot delete " + off);
        } else {
            Installer.write(off, "");
            // the agent must not go on reading what it had learned
            new File(Ubuntu.rootfs(c), "root/.config/termant/learned.md").delete();
        }
    }

    /** The notes, oldest first; each line of the file is "date, tab, text". */
    static List<String> notes(Context c) {
        List<String> notes = new ArrayList<>();
        for (String line : lines(notesFile(c))) {
            int tab = line.indexOf('\t');
            notes.add(tab < 0 ? line : line.substring(tab + 1));
        }
        return notes;
    }

    /** Removes the note at this place in notes(). */
    static void forget(Context c, int index) throws IOException {
        List<String> lines = lines(notesFile(c));
        if (index < 0 || index >= lines.size()) return;
        lines.remove(index);
        StringBuilder b = new StringBuilder();
        for (String line : lines) b.append(line).append('\n');
        Installer.write(notesFile(c), b.toString());
    }

    /** The tools the agent made: {command name, what it does}, by name. */
    static List<String[]> tools(Context c) {
        List<String[]> tools = new ArrayList<>();
        File[] files = new File(Ubuntu.rootfs(c), TOOLS).listFiles();
        if (files == null) return tools;
        Arrays.sort(files);
        for (File f : files) {
            String about = aboutOf(f);
            if (about != null) tools.add(new String[]{f.getName(), about});
        }
        return tools;
    }

    /** Removes a tool; only a file the agent saved as a tool is ever deleted. */
    static void drop(Context c, String name) throws IOException {
        File f = new File(new File(Ubuntu.rootfs(c), TOOLS), name);
        if (name.contains("/") || aboutOf(f) == null) return;
        if (!f.delete()) throw new IOException("cannot delete " + f);
    }

    /** The description in a tool's first lines, or null when the file is not a tool. */
    private static String aboutOf(File f) {
        if (!f.isFile()) return null;
        byte[] head = new byte[600];
        int n;
        try (InputStream in = new FileInputStream(f)) {
            n = in.read(head);
        } catch (IOException e) {
            return null;
        }
        if (n <= 0) return null;
        String[] lines = new String(head, 0, n, StandardCharsets.UTF_8).split("\n");
        for (int i = 0; i < lines.length && i < 3; i++) {
            if (lines[i].startsWith(MARK)) return lines[i].substring(MARK.length()).trim();
        }
        return null;
    }

    private static List<String> lines(File f) {
        List<String> lines = new ArrayList<>();
        if (!f.isFile()) return lines;
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            for (String line : new String(b.toByteArray(), StandardCharsets.UTF_8).split("\n")) {
                if (!line.trim().isEmpty()) lines.add(line);
            }
        } catch (IOException e) {
            android.util.Log.e("Termant", "cannot read " + f, e);
        }
        return lines;
    }
}
