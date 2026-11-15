package com.termant.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.system.Os;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** The agent's settings: model, workspace folder and the user's own API keys. */
final class Config {
    /** Model services a key can be entered for: {name shown, environment variable OpenCode reads}. */
    static final String[][] PROVIDERS = {
            {"Anthropic (Claude)", "ANTHROPIC_API_KEY"},
            {"OpenAI (GPT)", "OPENAI_API_KEY"},
            {"Google Gemini", "GOOGLE_GENERATIVE_AI_API_KEY"},
            {"OpenRouter", "OPENROUTER_API_KEY"},
            {"Groq", "GROQ_API_KEY"},
            {"DeepSeek", "DEEPSEEK_API_KEY"},
            {"Cerebras", "CEREBRAS_API_KEY"},
            {"Mistral", "MISTRAL_API_KEY"},
    };
    /** Free models to fall back on before the real list has been fetched. */
    private static final String[] KNOWN_FREE = {
            "opencode/big-pickle", "opencode/glm-5.3-flash-free",
            "opencode/deepseek-v4.1-flash-free", "opencode/minimax-m3.1-flash-free",
    };
    /** How long the app stays on a model it switched to before trying the chosen one again. */
    private static final long SWITCH_LASTS_MS = 3 * 60 * 60 * 1000;
    static final int MAX_ATTEMPTS = 5;
    static final String DEFAULT_WORKSPACE = "/root/workspace";
    /** Path inside Ubuntu of the file with the keys; the chat and login shells read it. */
    static final String KEYS_FILE = "/root/.config/termant/keys.env";

    private static final Pattern KEY_LINE = Pattern.compile("^export ([A-Z0-9_]+)='([^']*)'$");

    private Config() {
    }

    private static SharedPreferences prefs(Context c) {
        return c.getSharedPreferences("termant", Context.MODE_PRIVATE);
    }

    // ------------------------------------------------------------ model

    /** "provider/model", or "" to let OpenCode choose (a free model unless a key is set). */
    static String model(Context c) {
        return prefs(c).getString("model", "");
    }

    static void setModel(Context c, String model) {
        prefs(c).edit().putString("model", model).remove("switched").apply();
    }

    /** The model in use right now: the chosen one, or the one auto-switch moved to. */
    static String activeModel(Context c) {
        SharedPreferences p = prefs(c);
        String switched = p.getString("switched", "");
        if (!switched.isEmpty() && autoSwitch(c)
                && System.currentTimeMillis() - p.getLong("switched_at", 0) < SWITCH_LASTS_MS) {
            return switched;
        }
        return model(c);
    }

    /** Remembers that the chosen model stopped answering and this one worked. */
    static void setSwitched(Context c, String model) {
        prefs(c).edit().putString("switched", model).putLong("switched_at", System.currentTimeMillis()).apply();
    }

    static String modelName(Context c) {
        String m = activeModel(c);
        return m.isEmpty() ? c.getString(R.string.model_auto) : m.substring(m.lastIndexOf('/') + 1);
    }

    // ------------------------------------------------------------ auto-switch

    static boolean autoSwitch(Context c) {
        return prefs(c).getBoolean("auto_switch", true);
    }

    static void setAutoSwitch(Context c, boolean on) {
        prefs(c).edit().putBoolean("auto_switch", on).apply();
    }

    static boolean isFree(String model) {
        return model.startsWith("opencode/") || model.endsWith(":free") || model.endsWith("-free");
    }

    /** Backup models the user picked; empty means "all free models". */
    static List<String> chosenBackups(Context c) {
        String all = prefs(c).getString("backups", "");
        return all.isEmpty() ? new ArrayList<String>() : new ArrayList<>(Arrays.asList(all.split("\n")));
    }

    static void setBackups(Context c, List<String> models) {
        StringBuilder b = new StringBuilder();
        for (String m : models) b.append(b.length() > 0 ? "\n" : "").append(m);
        prefs(c).edit().putString("backups", b.toString()).apply();
    }

    /** Models to try for one message, in order: the active one, then the backups. */
    static List<String> attempts(Context c) {
        List<String> order = new ArrayList<>();
        String first = activeModel(c);
        // With a key saved, OpenCode's own choice could be a model that costs
        // money; "Automatic" must stay free, so name the free default.
        if (first.isEmpty() && !keys(c).isEmpty()) first = KNOWN_FREE[0];
        order.add(first);
        if (!autoSwitch(c)) return order;
        List<String> backups = chosenBackups(c);
        if (backups.isEmpty()) {
            for (String m : cachedModels(c)) if (isFree(m)) backups.add(m);
            if (backups.isEmpty()) backups.addAll(Arrays.asList(KNOWN_FREE));
            // "Automatic" most likely means this one already, so it goes last
            if (first.isEmpty() && backups.remove("opencode/big-pickle")) backups.add("opencode/big-pickle");
        }
        for (String m : backups) {
            if (order.size() >= MAX_ATTEMPTS) break;
            if (!order.contains(m)) order.add(m);
        }
        return order;
    }

    static List<String> cachedModels(Context c) {
        String all = prefs(c).getString("models", "");
        return all.isEmpty() ? new ArrayList<String>() : new ArrayList<>(Arrays.asList(all.split("\n")));
    }

    static void setCachedModels(Context c, List<String> models) {
        StringBuilder b = new StringBuilder();
        for (String m : models) b.append(b.length() > 0 ? "\n" : "").append(m);
        prefs(c).edit().putString("models", b.toString()).apply();
    }

    /** The chat whose message OpenCode handled last; only that one can be resumed with "--continue". */
    static String lastChat(Context c) {
        return prefs(c).getString("last_chat", "");
    }

    static void setLastChat(Context c, String id) {
        prefs(c).edit().putString("last_chat", id).apply();
    }

    // ------------------------------------------------------------ workspace

    /** Folder inside Ubuntu where the agent reads, writes and runs files. */
    static String workspace(Context c) {
        return prefs(c).getString("workspace", DEFAULT_WORKSPACE);
    }

    static void setWorkspace(Context c, String path) {
        prefs(c).edit().putString("workspace", path).apply();
    }

    static String workspaceName(Context c) {
        String w = workspace(c);
        return w.startsWith("/root/") ? "~/" + w.substring(6) : w;
    }

    /** Folders that can be chosen: the ones in Ubuntu's home, and phone storage if allowed. */
    static List<String> workspaces(Context c) {
        List<String> found = new ArrayList<>();
        File[] dirs = new File(Ubuntu.rootfs(c), "root").listFiles();
        if (dirs != null) {
            for (File d : dirs) {
                if (d.isDirectory() && !d.getName().startsWith(".")) found.add("/root/" + d.getName());
            }
        }
        if (!found.contains(DEFAULT_WORKSPACE)) found.add(DEFAULT_WORKSPACE);
        Collections.sort(found);
        if (Ubuntu.hasStorageAccess(c)) found.add("/sdcard");
        return found;
    }

    // ------------------------------------------------------------ API keys

    /** Environment variable name to key. */
    static Map<String, String> keys(Context c) {
        Map<String, String> keys = new LinkedHashMap<>();
        File f = new File(Ubuntu.rootfs(c), KEYS_FILE.substring(1));
        if (!f.isFile()) return keys;
        try (BufferedReader in = new BufferedReader(new FileReader(f))) {
            String line;
            while ((line = in.readLine()) != null) {
                Matcher m = KEY_LINE.matcher(line);
                if (m.matches()) keys.put(m.group(1), m.group(2));
            }
        } catch (IOException e) {
            // unreadable: treated as no keys
        }
        return keys;
    }

    /** Saves a key, or removes it when value is empty. Returns false if the key has characters a key never has. */
    static boolean setKey(Context c, String name, String value) throws IOException {
        value = value.trim();
        if (!value.matches("[\\x21-\\x7e]*") || value.contains("'")) return false;
        Map<String, String> keys = keys(c);
        if (value.isEmpty()) keys.remove(name);
        else keys.put(name, value);
        StringBuilder b = new StringBuilder("# Written by Termant's settings screen.\n");
        for (Map.Entry<String, String> e : keys.entrySet()) {
            b.append("export ").append(e.getKey()).append("='").append(e.getValue()).append("'\n");
        }
        File f = new File(Ubuntu.rootfs(c), KEYS_FILE.substring(1));
        Installer.write(f, b.toString());
        try {
            Os.chmod(f.getPath(), 0600);
        } catch (Exception e) {
            throw new IOException(e.getMessage());
        }
        // a key changes which models exist
        prefs(c).edit().remove("models").apply();
        return true;
    }
}
