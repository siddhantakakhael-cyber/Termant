package com.termant.app;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The chat with the AI agent. Each message is handed to OpenCode inside
 * Ubuntu (`opencode run`), and its printed output is turned into a reply and
 * a list of the commands the agent ran.
 */
final class Chat {
    static final class Message {
        boolean user;
        String text = "";
        /** Commands the agent ran for this reply. */
        final List<String> steps = new ArrayList<>();
        boolean failed;
        /** Set when the app moved to another model to get this reply. */
        String note = "";
    }

    interface Listener {
        void onChatChanged();
    }

    private static final String MARKER = "@@TERMANT-RUN@@";
    private static final int SETUP_FAILED = 97;
    private static final String SESSION_MARKER = "@@TERMANT-SESSION@@";
    private static final Pattern SESSION_ID = Pattern.compile("\\bses_[A-Za-z0-9]+");
    /**
     * Runs inside Ubuntu; $1 message, $2 "--continue", "--session ID" or empty, $3 workspace folder,
     * $4 model or empty, $5 non-empty to also print which OpenCode session the reply belongs to.
     */
    private static final String SCRIPT =
            "termant-agent --install-only || exit " + SETUP_FAILED + "\n"
                    + "export PATH=\"$HOME/.opencode/bin:$PATH\" NO_COLOR=1\n"
                    + "[ -f " + Config.KEYS_FILE + " ] && . " + Config.KEYS_FILE + "\n"
                    + "mkdir -p \"$3\" && cd \"$3\" || exit " + SETUP_FAILED + "\n"
                    + "echo " + MARKER + "\n"
                    + "if [ -n \"$4\" ]; then opencode run --standalone --auto $2 --model \"$4\" \"$1\" </dev/null\n"
                    + "else opencode run --standalone --auto $2 \"$1\" </dev/null; fi\n"
                    + "code=$?\n"
                    + "if [ $code -eq 0 ] && [ -n \"$5\" ]; then\n"
                    + "  echo; echo " + SESSION_MARKER + "\n"
                    + "  { opencode session list --standalone --max-count 1 --format json 2>/dev/null </dev/null"
                    + " || opencode session list --max-count 1 --format json 2>/dev/null </dev/null; } | head -c 3000\n"
                    + "fi\n"
                    + "exit $code\n";
    /** Prints the models that can be used, one "provider/model" per line; exit 3 if the agent is not downloaded yet. */
    private static final String MODELS_SCRIPT =
            "export PATH=\"$HOME/.opencode/bin:$PATH\" NO_COLOR=1\n"
                    + "command -v opencode >/dev/null 2>&1 || exit 3\n"
                    + "[ -f " + Config.KEYS_FILE + " ] && . " + Config.KEYS_FILE + "\n"
                    + "mkdir -p \"$1\" && cd \"$1\"\n"
                    + "opencode models --standalone 2>/dev/null </dev/null || opencode models </dev/null\n";
    private static final Pattern MODEL = Pattern.compile("^[A-Za-z0-9._-]+/[A-Za-z0-9._:/@-]+$");

    interface ModelsCallback {
        /** models is null when the agent has not been downloaded yet or the list could not be read. */
        void onModels(List<String> models);
    }

    /** Asks OpenCode which models exist (free ones, plus those of any provider with a key). */
    void loadModels(final ModelsCallback callback) {
        final String workspace = Config.workspace(context);
        new Thread("termant-models") {
            @Override
            public void run() {
                List<String> found = null;
                try {
                    Ubuntu.prepareHost(context);
                    ProcessBuilder pb = new ProcessBuilder(Ubuntu.command(context,
                            "/bin/bash", "-c", MODELS_SCRIPT, "termant-models", workspace));
                    Map<String, String> env = pb.environment();
                    env.clear();
                    for (String e : Ubuntu.hostEnvironment(context)) {
                        int eq = e.indexOf('=');
                        env.put(e.substring(0, eq), e.substring(eq + 1));
                    }
                    pb.redirectErrorStream(true);
                    Process p = pb.start();
                    p.getOutputStream().close();
                    java.io.BufferedReader in = new java.io.BufferedReader(
                            new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                    List<String> lines = new ArrayList<>();
                    String line;
                    while ((line = in.readLine()) != null) {
                        line = ANSI.matcher(line).replaceAll("").trim();
                        if (MODEL.matcher(line).matches()) lines.add(line);
                    }
                    if (p.waitFor() == 0 && !lines.isEmpty()) found = lines;
                } catch (Exception e) {
                    android.util.Log.e("Termant", "cannot list models", e);
                }
                final List<String> result = found;
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        if (result != null) Config.setCachedModels(context, result);
                        callback.onModels(result);
                    }
                });
            }
        }.start();
    }

    /** The next message starts a new conversation in OpenCode (used when the folder changes). */
    void startOver() {
        continues = false;
        session = "";
        save();
    }

    private static final Pattern ANSI = Pattern.compile("\u001b\\[[0-9;?]*[ -/]*[@-~]|\u001b\\][^\u0007]*\u0007|\r");
    /** OpenCode prints "> agent · model" before each thing the model says or does. */
    private static final Pattern HEADER = Pattern.compile("^> \\S+ \u00b7 \\S.*$");

    final List<Message> messages = new ArrayList<>();
    /** What is happening while busy, e.g. the setup progress; may be empty. */
    String status = "";

    private final Context context;
    private final File store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private Process process;
    private int run; // identifies the current request; stale threads are ignored
    private boolean busy;
    private boolean continues;
    /** The conversation on screen: its id, the OpenCode session it lives in and the folder it was held in. */
    private String id = newId();
    private String session = "";
    private String workspace = "";
    /** When the reply now being awaited was asked for (elapsed-realtime clock). */
    long startedAt;
    /** Finished and unfinished conversations, one file each. */
    private final File archive;

    /** One line of the list of earlier chats. */
    static final class Summary {
        String id;
        String title;
        long updated;
    }

    Chat(Context context) {
        this.context = context.getApplicationContext();
        store = new File(context.getFilesDir(), "chat.json");
        archive = new File(context.getFilesDir(), "chats");
        load(store);
        if (!messages.isEmpty()) save(); // a chat from an older version joins the list
    }

    private static String newId() {
        return "c" + System.currentTimeMillis();
    }

    String id() {
        return id;
    }

    /** Earlier chats, newest first (at most 100). */
    List<Summary> history() {
        List<Summary> all = new ArrayList<>();
        File[] files = archive.listFiles();
        if (files == null) return all;
        for (File f : files) {
            try {
                JSONObject o = new JSONObject(read(f));
                Summary s = new Summary();
                s.id = o.getString("id");
                s.title = o.optString("title");
                s.updated = o.optLong("updated");
                all.add(s);
            } catch (Exception e) {
                // not a chat file
            }
        }
        java.util.Collections.sort(all, new java.util.Comparator<Summary>() {
            @Override
            public int compare(Summary a, Summary b) {
                return Long.compare(b.updated, a.updated);
            }
        });
        return all.size() > 100 ? new ArrayList<>(all.subList(0, 100)) : all;
    }

    /** Brings an earlier chat back on screen, to read it or carry on with it. */
    void open(String chatId) {
        File f = new File(archive, chatId + ".json");
        if (chatId.equals(id) || !f.isFile()) return;
        stop();
        load(f);
        // its OpenCode session lives in the folder the chat was held in
        if (!workspace.isEmpty()) Config.setWorkspace(context, workspace);
        save();
        changed();
    }

    void delete(String chatId) {
        new File(archive, chatId + ".json").delete();
        if (chatId.equals(id)) clear();
        else changed();
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean isBusy() {
        return busy;
    }

    /** Starts a new, empty chat. The one on screen stays in the list of earlier chats. */
    void clear() {
        stop();
        messages.clear();
        continues = false;
        id = newId();
        session = "";
        workspace = "";
        save();
        changed();
    }

    void stop() {
        if (!busy) return;
        run++;
        busy = false;
        status = "";
        if (process != null) process.destroy();
        process = null;
        Message last = messages.get(messages.size() - 1);
        if (last.text.isEmpty()) last.text = context.getString(R.string.chat_stopped);
        save();
        changed();
    }

    void send(String text) {
        final String request = text.trim();
        if (request.isEmpty() || busy) return;
        Message mine = new Message();
        mine.user = true;
        mine.text = request;
        messages.add(mine);
        final Message reply = new Message();
        messages.add(reply);
        busy = true;
        status = "";
        startedAt = android.os.SystemClock.elapsedRealtime();
        final int id = ++run;
        final String workspace = Config.workspace(context);
        this.workspace = workspace;
        // Carry on in this chat's own OpenCode session. Without a known session,
        // "--continue" is only right if this chat was the last one to run.
        final String cont = !session.isEmpty() ? "--session " + session
                : continues && this.id.equals(Config.lastChat(context)) ? "--continue" : "";
        final String wantSession = session.isEmpty() ? "1" : "";
        final List<String> models = Config.attempts(context);
        save();
        changed();

        new Thread("termant-chat") {
            @Override
            public void run() {
                for (int attempt = 0; ; attempt++) {
                    final String model = models.get(attempt);
                    int exit = -1;
                    boolean stuck = false;
                    final StringBuilder out = new StringBuilder();
                    try {
                        Ubuntu.prepareHost(context);
                        ProcessBuilder pb = new ProcessBuilder(Ubuntu.command(context,
                                "/bin/bash", "-c", SCRIPT, "termant-chat", request, cont,
                                workspace, model, wantSession));
                        Map<String, String> env = pb.environment();
                        env.clear();
                        for (String e : Ubuntu.hostEnvironment(context)) {
                            int eq = e.indexOf('=');
                            env.put(e.substring(0, eq), e.substring(eq + 1));
                        }
                        pb.redirectErrorStream(true);
                        final Process p = pb.start();
                        p.getOutputStream().close();
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (id == Chat.this.run) process = p;
                                else p.destroy();
                            }
                        });
                        Reader in = new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8);
                        char[] buf = new char[4096];
                        int n;
                        long shown = 0;
                        // With another model to move to, do not sit through OpenCode's own retries.
                        int patience = attempt + 1 < models.size() ? RETRIES_BEFORE_SWITCH : RETRIES_BEFORE_GIVING_UP;
                        while ((n = in.read(buf)) > 0) {
                            out.append(buf, 0, n);
                            if (!stuck && retries(out.toString()) >= patience) {
                                stuck = true;
                                p.destroy();
                            }
                            long now = System.currentTimeMillis();
                            if (now - shown > 150) { // do not redraw for every few bytes
                                shown = now;
                                publish(id, reply, out.toString(), null);
                            }
                        }
                        exit = p.waitFor();
                        if (stuck) exit = STUCK;
                    } catch (Exception e) {
                        out.append('\n').append(e);
                    }
                    if (id != Chat.this.run) return; // stopped by the user
                    if (attempt + 1 < models.size() && modelGaveUp(out.toString(), exit)) {
                        final String next = models.get(attempt + 1);
                        android.util.Log.i("Termant", "model '" + model + "' gave no answer, switching to " + next);
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (id != Chat.this.run) return;
                                reply.text = "";
                                reply.note = context.getString(R.string.chat_switched, shortName(next),
                                        model.isEmpty() ? context.getString(R.string.model_auto) : shortName(model));
                                changed();
                            }
                        });
                        continue;
                    }
                    final boolean switched = attempt > 0 && exit == 0;
                    if (switched) Config.setSwitched(context, model);
                    else if (attempt > 0) {
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                reply.note = context.getString(R.string.chat_all_failed);
                            }
                        });
                    }
                    publish(id, reply, out.toString(), exit);
                    return;
                }
            }
        }.start();
    }

    private static final int STUCK = 75;
    private static final int RETRIES_BEFORE_SWITCH = 3;
    private static final int RETRIES_BEFORE_GIVING_UP = 6;
    /** OpenCode prints this each time the model's service fails and it asks again. */
    private static final Pattern RETRYING = Pattern.compile("(?m)^! retrying \\(attempt (\\d+)\\)");

    /**
     * How often OpenCode has already retried, as long as the agent has done
     * nothing yet; 0 once it has run a command, so its work is never cut off.
     */
    static int retries(String raw) {
        String output = ANSI.matcher(raw).replaceAll("");
        int start = output.indexOf(MARKER);
        if (start < 0) return 0;
        output = output.substring(start);
        if (output.contains(SESSION_MARKER)) return 0; // the answer is complete
        if (!parse(output).steps.isEmpty()) return 0;
        int highest = 0;
        Matcher m = RETRYING.matcher(output);
        while (m.find()) highest = Math.max(highest, Integer.parseInt(m.group(1)));
        return highest;
    }

    private static String shortName(String model) {
        return model.substring(model.lastIndexOf('/') + 1);
    }

    private static final Pattern LIMIT = Pattern.compile(
            "(?i)rate.?limit|quota|\\b429\\b|too many requests|usage limit|limit (reached|exceeded)|"
                    + "insufficient|out of credits|overloaded|unavailable|not found|unauthorized|invalid api key");

    /**
     * Whether this model failed in a way another model could fix: it ended
     * with an error or said nothing, typically because its free limit is used
     * up. Never true once the agent has run a command, because trying again
     * would do that work twice.
     */
    static boolean modelGaveUp(String raw, int exit) {
        String output = ANSI.matcher(raw).replaceAll("");
        int start = output.indexOf(MARKER);
        if (start < 0) return false; // the agent itself could not be set up
        String said = output.substring(start + MARKER.length());
        if (said.contains(SESSION_MARKER)) said = said.substring(0, said.indexOf(SESSION_MARKER));
        Parsed parsed = parse(said);
        if (!parsed.steps.isEmpty()) return false;
        if (exit != 0 || parsed.text.isEmpty()) return true;
        return said.length() < 800 && said.toLowerCase(java.util.Locale.US).contains("error")
                && LIMIT.matcher(said).find();
    }

    /** Shows the output so far; exit is null while the agent is still running. */
    private void publish(final int id, final Message reply, final String output, final Integer exit) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (id != run) return;
                apply(reply, output, exit);
                if (exit != null) {
                    busy = false;
                    process = null;
                    status = "";
                    if (exit == 0) {
                        continues = true;
                        Config.setLastChat(context, Chat.this.id);
                        int at = output.indexOf(SESSION_MARKER);
                        Matcher found = at < 0 ? null : SESSION_ID.matcher(output.substring(at));
                        if (found != null && found.find()) session = found.group();
                        android.util.Log.i("Termant", "chat " + Chat.this.id + " session: "
                                + (session.isEmpty() ? "unknown" : session));
                    }
                    if (exit == 0 && Config.cachedModels(context).isEmpty()) {
                        loadModels(new ModelsCallback() {
                            @Override
                            public void onModels(List<String> models) {
                                // stored by loadModels; nothing to show
                            }
                        });
                    }
                    save();
                }
                changed();
            }
        });
    }

    private void apply(Message reply, String raw, Integer exit) {
        String output = ANSI.matcher(raw).replaceAll("");
        int start = output.indexOf(MARKER);
        if (start < 0) {
            // still installing the agent: show the latest line as progress
            String[] lines = output.trim().split("\n");
            status = lines[lines.length - 1].trim();
            if (exit != null) {
                reply.failed = true;
                reply.text = context.getString(R.string.chat_setup_failed) + tail(output);
            }
            return;
        }
        status = "";
        int end = output.indexOf(SESSION_MARKER);
        if (end > start) output = output.substring(0, end);
        Parsed parsed = parse(output.substring(start + MARKER.length()));
        reply.steps.clear();
        reply.steps.addAll(parsed.steps);
        reply.text = parsed.text;
        if (exit != null && reply.text.isEmpty()) {
            reply.failed = exit != 0;
            String rest = output.substring(start + MARKER.length());
            reply.text = exit == 0 && !reply.steps.isEmpty()
                    ? context.getString(R.string.chat_done)
                    : context.getString(rest.contains("Provider returned error")
                    ? R.string.chat_provider_error : R.string.chat_no_answer) + tail(rest);
        }
    }

    private static String tail(String output) {
        String t = output.trim();
        if (t.isEmpty()) return "";
        String[] lines = t.split("\n");
        StringBuilder b = new StringBuilder("\n\n```\n");
        for (int i = Math.max(0, lines.length - 8); i < lines.length; i++) b.append(lines[i]).append('\n');
        return b.append("```").toString();
    }

    static final class Parsed {
        final List<String> steps = new ArrayList<>();
        String text = "";
    }

    /**
     * OpenCode's plain output is a series of blocks, each starting with a
     * "> agent · model" line. A block is either something the model says, or
     * "$ command" (or "← Write file") followed by that tool's output. The answer is the last
     * thing the model said; command output is left out.
     */
    static Parsed parse(String output) {
        Parsed result = new Parsed();
        StringBuilder said = new StringBuilder();
        boolean inCommand = false;
        for (String line : output.split("\n", -1)) {
            if (HEADER.matcher(line).matches()) {
                if (said.toString().trim().length() > 0) result.text = said.toString().trim();
                said.setLength(0);
                inCommand = false;
            } else if (line.startsWith("$ ")) {
                result.steps.add(line.substring(2).trim());
                inCommand = true;
            } else if (line.startsWith("\u2190 ") || line.startsWith("\u2192 ")) {
                // file tools: "← Write hi.py", "→ Read notes.txt"
                result.steps.add(line.substring(2).trim());
                inCommand = true;
            } else if (line.startsWith("! retrying (attempt ")) {
                // OpenCode's own progress line, not something the model said
                inCommand = false;
            } else if (!inCommand) {
                said.append(line).append('\n');
            }
        }
        if (said.toString().trim().length() > 0) result.text = said.toString().trim();
        return result;
    }

    private void changed() {
        if (listener != null) listener.onChatChanged();
    }

    // ------------------------------------------------------------ storage

    private void save() {
        try {
            JSONArray list = new JSONArray();
            String title = "";
            for (Message m : messages) {
                if (title.isEmpty() && m.user) title = m.text.length() > 80 ? m.text.substring(0, 80) : m.text;
                list.put(new JSONObject().put("user", m.user).put("text", m.text)
                        .put("failed", m.failed).put("note", m.note).put("steps", new JSONArray(m.steps)));
            }
            String json = new JSONObject().put("id", id).put("title", title)
                    .put("updated", System.currentTimeMillis()).put("session", session)
                    .put("workspace", workspace).put("continues", continues).put("messages", list).toString();
            Installer.write(store, json);
            if (!messages.isEmpty()) Installer.write(new File(archive, id + ".json"), json);
        } catch (Exception e) {
            android.util.Log.e("Termant", "cannot save the chat", e);
        }
    }

    private static String read(File f) throws java.io.IOException {
        try (InputStream in = new FileInputStream(f)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new String(b.toByteArray(), StandardCharsets.UTF_8);
        }
    }

    private void load(File from) {
        if (!from.isFile()) return;
        try {
            JSONObject o = new JSONObject(read(from));
            List<Message> loaded = new ArrayList<>();
            JSONArray list = o.getJSONArray("messages");
            for (int i = 0; i < list.length(); i++) {
                JSONObject j = list.getJSONObject(i);
                Message m = new Message();
                m.user = j.optBoolean("user");
                m.text = j.optString("text");
                m.failed = j.optBoolean("failed");
                m.note = j.optString("note");
                JSONArray steps = j.optJSONArray("steps");
                for (int k = 0; steps != null && k < steps.length(); k++) m.steps.add(steps.getString(k));
                // a reply that was cut off when the app was closed
                if (!m.user && m.text.isEmpty()) m.text = context.getString(R.string.chat_stopped);
                loaded.add(m);
            }
            messages.clear();
            messages.addAll(loaded);
            continues = o.optBoolean("continues");
            id = o.optString("id", newId());
            session = o.optString("session");
            workspace = o.optString("workspace");
        } catch (Exception e) {
            android.util.Log.e("Termant", "cannot read a chat", e);
        }
    }
}
