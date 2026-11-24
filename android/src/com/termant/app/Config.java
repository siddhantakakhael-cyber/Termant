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
    /** Optional keys for web search services: {name shown, environment variable, OpenCode's name for it}. */
    static final String[][] SEARCH_KEYS = {
            {"Exa", "EXA_API_KEY", "exa"},
            {"Tavily", "TAVILY_API_KEY", "tavily"},
    };
    /** The GitHub token; `gh` reads it by this name, and github.sh hands it to git. */
    static final String GITHUB_KEY = "GH_TOKEN", GITHUB_USER = "GITHUB_USER", GITHUB_EMAIL = "GITHUB_EMAIL";
    /** Path inside Ubuntu of the file that signs git and gh in with that token. */
    static final String GITHUB_FILE = "/usr/local/lib/termant/github.sh";
    /** Free models to fall back on before the real list has been fetched. */
    private static final String[] KNOWN_FREE = {
            "opencode/big-pickle", "opencode/glm-5.3-flash-free",
            "opencode/deepseek-v4.1-flash-free", "opencode/minimax-m3.1-flash-free",
    };
    /** Termant's own commands in Ubuntu (guest/kit); the agent may run them without asking. */
    static final String[] KIT_TOOLS = {"safe", "tidy", "study", "readfile", "me", "watch", "diary", "draw", "apilayer"};
    /** Shell commands OpenCode refuses outright, for the main agent and its helpers. */
    static final String[] NEVER = {
            "rm /sdcard*", "rm -* /sdcard*", "rm /storage*", "rm -* /storage*",
            "rm -* /", "rm -* ~", "rm -* ~/", "rm -* /root", "rm -* /root/",
            "mkfs*", "dd * of=/dev/*",
    };
    /** How long the app stays on a model it switched to before trying the chosen one again. */
    private static final long SWITCH_LASTS_MS = 3 * 60 * 60 * 1000;
    static final int MAX_ATTEMPTS = 5;
    /** Path inside Ubuntu of the file with the agent's notes and own tools, which OpenCode reads in every chat. */
    static final String LEARNED_FILE = "/root/.config/termant/learned.md";
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
        SharedPreferences.Editor e = prefs(c).edit().putString("model", model).remove("switched");
        // Chosen by hand: try it first, even if OpenCode's free allowance was used up a while ago.
        if (onFreeAllowance(model)) e.remove("free_limit_until");
        e.apply();
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

    /** Whether the agent waits for the words "hey Termant" in the background. */
    static boolean wakeWord(Context c) {
        return prefs(c).getBoolean("wake_word", false);
    }

    static void setWakeWord(Context c, boolean on) {
        prefs(c).edit().putBoolean("wake_word", on).apply();
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
        String first = activeModel(c);
        // With a key saved, OpenCode's own choice could be a model that costs
        // money; "Automatic" must stay free, so name the free default.
        if (first.isEmpty() && hasModelKey(c)) first = KNOWN_FREE[0];
        return order(first, autoSwitch(c), chosenBackups(c), cachedModels(c), freeLimitUntil(c) > 0);
    }

    /**
     * The order itself, kept apart from the stored settings so that it can be
     * checked on its own. "chosen" are the backups the user ticked, "known"
     * every model OpenCode listed; freeUsedUp says that OpenCode's free
     * allowance is known to be used up right now.
     */
    static List<String> order(String first, boolean autoSwitch, List<String> chosen, List<String> known,
            boolean freeUsedUp) {
        List<String> all = new ArrayList<>();
        all.add(first);
        if (!autoSwitch) return all;
        List<String> backups = new ArrayList<>(chosen);
        if (backups.isEmpty()) {
            List<String> free = new ArrayList<>();
            for (String m : known) if (isFree(m)) free.add(m);
            if (free.isEmpty()) free.addAll(Arrays.asList(KNOWN_FREE));
            // "Automatic" most likely means this one already, so it goes last
            if (first.isEmpty() && free.remove("opencode/big-pickle")) free.add("opencode/big-pickle");
            backups = mixed(free);
        }
        for (String m : backups) if (!all.contains(m)) all.add(m);
        if (freeUsedUp) {
            // Do not start with models that are known to refuse: the others go first.
            List<String> usable = new ArrayList<>(), waiting = new ArrayList<>();
            for (String m : all) (onFreeAllowance(m) ? waiting : usable).add(m);
            all.clear();
            all.addAll(usable);
            all.addAll(waiting);
        }
        List<String> order = new ArrayList<>(all.subList(0, Math.min(MAX_ATTEMPTS, all.size())));
        // OpenCode's free models stop together, so keep one model of another service within reach.
        boolean elsewhere = false;
        for (String m : order) elsewhere |= !onFreeAllowance(m);
        if (!elsewhere) {
            for (String m : all.subList(order.size(), all.size())) {
                if (onFreeAllowance(m)) continue;
                order.set(order.size() - 1, m);
                break;
            }
        }
        return order;
    }

    /** The same models, taking one of each service in turn ("opencode/x", "openrouter/y", "opencode/z", ...). */
    private static List<String> mixed(List<String> models) {
        Map<String, List<String>> byService = new LinkedHashMap<>();
        for (String m : models) {
            String service = m.contains("/") ? m.substring(0, m.indexOf('/')) : "";
            if (!byService.containsKey(service)) byService.put(service, new ArrayList<String>());
            byService.get(service).add(m);
        }
        List<String> out = new ArrayList<>();
        for (int i = 0; out.size() < models.size(); i++) {
            for (List<String> of : byService.values()) if (i < of.size()) out.add(of.get(i));
        }
        return out;
    }

    // ------------------------------------------------------------ OpenCode's free allowance

    /** OpenCode's refusal does not always say when free use comes back; then look again after this long. */
    static final long FREE_LIMIT_GUESS_MS = 60 * 60 * 1000;
    private static final long FREE_LIMIT_MAX_MS = 45L * 24 * 60 * 60 * 1000;

    /**
     * Whether a model runs on OpenCode's free allowance: its own models, and
     * "" which lets OpenCode choose one of them. That allowance is counted
     * per network, so when one of these models refuses, all of them do.
     */
    static boolean onFreeAllowance(String model) {
        return model.isEmpty() || model.startsWith("opencode/");
    }

    /** Until when OpenCode's free allowance is known to be used up; 0 when it is not. */
    static long freeLimitUntil(Context c) {
        long until = prefs(c).getLong("free_limit_until", 0);
        return until > System.currentTimeMillis() ? until : 0;
    }

    /** Remembers that OpenCode refused free use; "until" is 0 when it did not say for how long. */
    static long setFreeLimitUntil(Context c, long until) {
        long now = System.currentTimeMillis();
        if (until <= now || until - now > FREE_LIMIT_MAX_MS) until = now + FREE_LIMIT_GUESS_MS;
        prefs(c).edit().putLong("free_limit_until", until).apply();
        return until;
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

    /** Whether a key of a model service is saved (the file also holds GitHub and search keys). */
    static boolean hasModelKey(Context c) {
        Map<String, String> keys = keys(c);
        for (String[] provider : PROVIDERS) if (keys.containsKey(provider[1])) return true;
        return false;
    }

    /** Saves a key, or removes it when value is empty. Returns false if the key has characters a key never has. */
    static boolean setKey(Context c, String name, String value) throws IOException {
        Map<String, String> one = new LinkedHashMap<>();
        one.put(name, value);
        return setKeys(c, one);
    }

    /** Saves several values at once; an empty value removes that name. */
    static boolean setKeys(Context c, Map<String, String> changes) throws IOException {
        Map<String, String> keys = keys(c);
        for (Map.Entry<String, String> change : changes.entrySet()) {
            String value = change.getValue().trim();
            if (!value.matches("[\\x21-\\x7e]*") || value.contains("'")) return false;
            if (value.isEmpty()) keys.remove(change.getKey());
            else keys.put(change.getKey(), value);
        }
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

    // ------------------------------------------------------------ GitHub

    /** The account name of the saved GitHub token; "" when none is saved, "?" when it could not be checked. */
    static String githubUser(Context c) {
        Map<String, String> keys = keys(c);
        if (!keys.containsKey(GITHUB_KEY)) return "";
        String user = keys.get(GITHUB_USER);
        return user == null ? "?" : user;
    }

    /** Saves the token with the account it belongs to (login may be empty if unknown); an empty token signs out. */
    static boolean setGithub(Context c, String token, String login, String email) throws IOException {
        Map<String, String> changes = new LinkedHashMap<>();
        changes.put(GITHUB_KEY, token);
        changes.put(GITHUB_USER, token.isEmpty() ? "" : login);
        changes.put(GITHUB_EMAIL, token.isEmpty() ? "" : email);
        return setKeys(c, changes);
    }

    // ------------------------------------------------------------ web search

    static boolean webSearch(Context c) {
        return prefs(c).getBoolean("web_search", true);
    }

    static void setWebSearch(Context c, boolean on) {
        prefs(c).edit().putBoolean("web_search", on).apply();
    }

    /** The search service in use: one the user has a key for, else Exa, which works without one. */
    static String searchProvider(Context c) {
        Map<String, String> keys = keys(c);
        for (String[] service : SEARCH_KEYS) if (keys.containsKey(service[1])) return service[2];
        return "exa";
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

    /** Writes opencode.jsonc (the phone-control permissions, web search and the
     *  user's MCP servers) into Ubuntu's OpenCode config dir, so the agent picks the MCP
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
            JSONArray perms = new JSONArray();
            perms.put(new JSONObject().put("action", "shell").put("resource", "phone *").put("effect", "allow"));
            perms.put(new JSONObject().put("action", "shell").put("resource", "date *").put("effect", "allow"));
            perms.put(new JSONObject().put("action", "shell").put("resource", "learn *").put("effect", "allow"));
            perms.put(new JSONObject().put("action", "shell").put("resource", "team *").put("effect", "allow"));
            for (String tool : KIT_TOOLS) {
                perms.put(new JSONObject().put("action", "shell").put("resource", tool + " *").put("effect", "allow"));
            }
            // Never, whoever asks (helpers too): wiping the phone's storage or the system. Deleting
            // the user's files goes through `safe rm`, which keeps them and asks before a folder.
            for (String never : NEVER) {
                perms.put(new JSONObject().put("action", "shell").put("resource", never).put("effect", "deny"));
            }
            // What the agent learned in earlier chats (notes, own tools); "learn refresh" writes the file.
            o.put("instructions", new JSONArray().put(LEARNED_FILE));
            // Without a search service named here OpenCode asks which one to use, and in a chat nobody can answer.
            if (webSearch(c)) {
                perms.put(new JSONObject().put("action", "websearch").put("resource", "*").put("effect", "allow"));
                perms.put(new JSONObject().put("action", "webfetch").put("resource", "*").put("effect", "allow"));
                o.put("websearch", new JSONObject().put("provider", searchProvider(c)));
            } else {
                o.put("websearch", false);
            }
            o.put("permissions", perms);
            JSONObject servers = new JSONObject();
            for (Map.Entry<String, String> e : mcpServers(c).entrySet()) {
                // the command line as typed, run by the shell so that quotes and pipes work
                JSONObject s = new JSONObject();
                s.put("type", "local");
                s.put("command", new JSONArray().put("/bin/bash").put("-lc").put(e.getValue()));
                // "npx -y ..." downloads the server on its first start
                s.put("timeout", new JSONObject().put("startup", 120000));
                servers.put(e.getKey(), s);
            }
            if (servers.length() > 0) o.put("mcp", new JSONObject().put("servers", servers));
            Installer.write(conf, o.toString(2) + "\n");
            try { Os.chmod(conf.getPath(), 0644); } catch (Exception ignored) {}
        } catch (Exception e) {
            // Ubuntu not installed yet: the first agent run copies the template instead
            Log.e("Termant", "cannot write MCP config", e);
        }
    }
}
