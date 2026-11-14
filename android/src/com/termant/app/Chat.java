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
    }

    interface Listener {
        void onChatChanged();
    }

    private static final String MARKER = "@@TERMANT-RUN@@";
    private static final int SETUP_FAILED = 97;
    /** Runs inside Ubuntu; $1 message, $2 "--continue" or empty, $3 workspace folder, $4 model or empty. */
    private static final String SCRIPT =
            "termant-agent --install-only || exit " + SETUP_FAILED + "\n"
                    + "export PATH=\"$HOME/.opencode/bin:$PATH\" NO_COLOR=1\n"
                    + "[ -f " + Config.KEYS_FILE + " ] && . " + Config.KEYS_FILE + "\n"
                    + "mkdir -p \"$3\" && cd \"$3\" || exit " + SETUP_FAILED + "\n"
                    + "echo " + MARKER + "\n"
                    + "if [ -n \"$4\" ]; then exec opencode run --standalone --auto $2 --model \"$4\" \"$1\" </dev/null; fi\n"
                    + "exec opencode run --standalone --auto $2 \"$1\" </dev/null\n";
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

    Chat(Context context) {
        this.context = context.getApplicationContext();
        store = new File(context.getFilesDir(), "chat.json");
        load();
    }

    void setListener(Listener l) {
        listener = l;
    }

    boolean isBusy() {
        return busy;
    }

    /** Forgets the conversation; the next message starts a fresh one. */
    void clear() {
        stop();
        messages.clear();
        continues = false;
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
        final int id = ++run;
        final boolean cont = continues;
        final String workspace = Config.workspace(context);
        final String model = Config.model(context);
        save();
        changed();

        new Thread("termant-chat") {
            @Override
            public void run() {
                int exit = -1;
                final StringBuilder out = new StringBuilder();
                try {
                    Ubuntu.prepareHost(context);
                    ProcessBuilder pb = new ProcessBuilder(Ubuntu.command(context,
                            "/bin/bash", "-c", SCRIPT, "termant-chat", request, cont ? "--continue" : "",
                            workspace, model));
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
                    while ((n = in.read(buf)) > 0) {
                        out.append(buf, 0, n);
                        long now = System.currentTimeMillis();
                        if (now - shown > 150) { // do not redraw for every few bytes
                            shown = now;
                            publish(id, reply, out.toString(), null);
                        }
                    }
                    exit = p.waitFor();
                } catch (Exception e) {
                    out.append('\n').append(e);
                }
                publish(id, reply, out.toString(), exit);
            }
        }.start();
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
                    if (exit == 0) continues = true;
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
        Parsed parsed = parse(output.substring(start + MARKER.length()));
        reply.steps.clear();
        reply.steps.addAll(parsed.steps);
        reply.text = parsed.text;
        if (exit != null && reply.text.isEmpty()) {
            reply.failed = exit != 0;
            reply.text = exit == 0 && !reply.steps.isEmpty()
                    ? context.getString(R.string.chat_done)
                    : context.getString(R.string.chat_no_answer) + tail(output.substring(start + MARKER.length()));
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
            for (Message m : messages) {
                list.put(new JSONObject().put("user", m.user).put("text", m.text)
                        .put("failed", m.failed).put("steps", new JSONArray(m.steps)));
            }
            Installer.write(store, new JSONObject().put("continues", continues).put("messages", list).toString());
        } catch (Exception e) {
            android.util.Log.e("Termant", "cannot save the chat", e);
        }
    }

    private void load() {
        if (!store.isFile()) return;
        try (InputStream in = new FileInputStream(store)) {
            java.io.ByteArrayOutputStream b = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            JSONObject o = new JSONObject(new String(b.toByteArray(), StandardCharsets.UTF_8));
            continues = o.optBoolean("continues");
            JSONArray list = o.getJSONArray("messages");
            for (int i = 0; i < list.length(); i++) {
                JSONObject j = list.getJSONObject(i);
                Message m = new Message();
                m.user = j.optBoolean("user");
                m.text = j.optString("text");
                m.failed = j.optBoolean("failed");
                JSONArray steps = j.optJSONArray("steps");
                for (int k = 0; steps != null && k < steps.length(); k++) m.steps.add(steps.getString(k));
                // a reply that was cut off when the app was closed
                if (!m.user && m.text.isEmpty()) m.text = context.getString(R.string.chat_stopped);
                messages.add(m);
            }
        } catch (Exception e) {
            messages.clear();
        }
    }
}
