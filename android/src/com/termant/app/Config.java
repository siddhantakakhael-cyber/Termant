package com.termant.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.system.Os;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

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
        return m.isEmpty() ? Lang.of(c).getString(R.string.model_auto) : m.substring(m.lastIndexOf('/') + 1);
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

    // ------------------------------------------------------------ MCP servers

    /** Name to command, for the MCP servers OpenCode may connect to as tools. */
    static Map<String, String> mcpServers(Context c) {
        try {
            JSONObject o = new JSONObject(prefs(c).getString("mcp_servers", "{}"));
            Map<String, String> servers = new LinkedHashMap<>();
            java.util.Iterator<String> names = o.keys();
            while (names.hasNext()) {
                String name = names.next();
                servers.put(name, o.getString(name));
            }
            return servers;
        } catch (Exception e) {
            return new LinkedHashMap<>();
        }
    }

    static void setMcpServers(Context c, Map<String, String> servers) {
        JSONObject o = new JSONObject();
        for (Map.Entry<String, String> e : servers.entrySet()) {
            try { o.put(e.getKey(), e.getValue()); } catch (Exception ignored) {}
        }
        prefs(c).edit().putString("mcp_servers", o.toString()).apply();
    }

    /**
     * An OpenCode plugin. OpenCode prints a paragraph of the model's answer
     * only once it is complete; this copies the words to the file named in
     * TERMANT_STREAM as they arrive, so the chat can show the answer while it
     * is being written. Without that variable (the terminal) it does nothing.
     */
    private static final String STREAM_PLUGIN =
            "// Written by Termant before every chat message; changes are overwritten.\n"
                    + "// Copies the model's words to a file as they arrive, for the chat screen.\n"
                    + "import { appendFileSync } from \"node:fs\"\n"
                    + "\n"
                    + "export const TermantStream = async () => {\n"
                    + "  const file = process.env.TERMANT_STREAM\n"
                    + "  if (!file) return {}\n"
                    + "  const kinds = new Map()\n"
                    + "  let session\n"
                    + "  const write = (line) => {\n"
                    + "    try { appendFileSync(file, JSON.stringify(line) + \"\\n\") } catch {}\n"
                    + "  }\n"
                    + "  return {\n"
                    + "    event: async ({ event }) => {\n"
                    + "      try {\n"
                    + "        const p = event && event.properties\n"
                    + "        if (!p) return\n"
                    + "        if (event.type === \"message.part.updated\" && p.part) {\n"
                    + "          const part = p.part\n"
                    + "          if (!session) session = part.sessionID\n"
                    + "          if (part.sessionID !== session) return // a sub-agent's words\n"
                    + "          if (!kinds.has(part.id)) {\n"
                    + "            kinds.set(part.id, part.type)\n"
                    + "            if (part.type === \"text\") write({ part: part.id })\n"
                    + "          }\n"
                    + "          // older OpenCode sends the new words along with the part\n"
                    + "          if (part.type === \"text\" && typeof p.delta === \"string\" && p.delta) {\n"
                    + "            write({ part: part.id, delta: p.delta })\n"
                    + "          }\n"
                    + "        } else if (event.type === \"message.part.delta\" && p.field === \"text\"\n"
                    + "            && typeof p.delta === \"string\" && kinds.get(p.partID) === \"text\") {\n"
                    + "          write({ part: p.partID, delta: p.delta })\n"
                    + "        }\n"
                    + "      } catch {}\n"
                    + "    },\n"
                    + "  }\n"
                    + "}\n";

    /** Writes opencode.jsonc (the phone-control permissions plus the user's MCP
     *  servers) into Ubuntu's OpenCode config dir, so the agent picks the MCP
     *  servers up on its next run. A no-op until Ubuntu is installed. */
    static void writeOpencodeConfig(Context c) {
        try {
            File root = Ubuntu.rootfs(c);
            File dir = new File(root, "root/.config/opencode");
            dir.mkdirs();
            File conf = new File(dir, "opencode.jsonc");
            JSONObject o = new JSONObject();
            o.put("$schema", "https://opencode.ai/config.json");
            // phone controls never ask first, also in the terminal's agent
            o.put("permission", new JSONObject().put("bash",
                    new JSONObject().put("phone *", "allow").put("date *", "allow")));
            JSONObject mcp = new JSONObject();
            for (Map.Entry<String, String> e : mcpServers(c).entrySet()) {
                // the command line as typed, run by the shell so that quotes and pipes work
                JSONObject s = new JSONObject();
                s.put("type", "local");
                s.put("command", new JSONArray().put("/bin/bash").put("-lc").put(e.getValue()));
                s.put("enabled", true);
                s.put("timeout", 120000); // "npx -y ..." downloads the server on its first start
                mcp.put(e.getKey(), s);
            }
            if (mcp.length() > 0) o.put("mcp", mcp);
            Installer.write(conf, o.toString(2) + "\n");
            try { Os.chmod(conf.getPath(), 0644); } catch (Exception ignored) {}
            Installer.write(new File(dir, "plugin/termant-stream.js"), STREAM_PLUGIN);
        } catch (Exception e) {
            // Ubuntu not installed yet: the first agent run copies the template instead
            Log.e("Termant", "cannot write MCP config", e);
        }
    }
}
