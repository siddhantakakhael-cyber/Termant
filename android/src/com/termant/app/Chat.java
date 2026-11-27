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
        /** The agent spoke first: a check-in nobody asked for (see Care). */
        boolean first;
        /**
         * Pictures made for this reply (`draw`), shown under it. Each is the file's path in Ubuntu;
         * after a tab comes its address in the phone's gallery when a copy was put there.
         */
        final List<String> pictures = new ArrayList<>();

        /** The file of picture i as the app reads it. */
        static File pictureFile(Context c, String picture) {
            String path = picture.split("\t", 2)[0];
            return new File(Ubuntu.rootfs(c), path.startsWith("/") ? path.substring(1) : path);
        }

        /** The picture's address in the phone's gallery, or "" when it is only in Ubuntu. */
        static String pictureAddress(String picture) {
            String[] parts = picture.split("\t", 2);
            return parts.length > 1 ? parts[1].trim() : "";
        }
    }

    interface Listener {
        void onChatChanged();
    }

    private static final String MARKER = "@@TERMANT-RUN@@";
    private static final int SETUP_FAILED = 97;
    private static final String SESSION_MARKER = "@@TERMANT-SESSION@@";
    private static final Pattern SESSION_ID = Pattern.compile("\\bses_[A-Za-z0-9]+");
    /** A note under a reply that starts with this sign can be tapped: it undoes the reply's file changes. */
    static final String UNDO_SIGN = "\u21a9";
    /** Where the agent script copies OpenCode's event feed while a reply is written, as seen from inside Ubuntu. */
    private static final String STREAM_FILE = "/root/.cache/termant/events.txt";
    /** Where `draw` (guest/kit) lists the pictures it made for the message being answered. */
    private static final String DRAWN_FILE = "/root/.cache/termant/drawn.txt";
    /**
     * Runs inside Ubuntu; $1 message, $2 "--continue", "--session ID" or empty, $3 workspace folder,
     * $4 model or empty, $5 non-empty to also print which OpenCode session the reply belongs to,
     * $6 the file the reply is copied to word by word.
     */
    private static final String SCRIPT =
            "rm -f \"$6.note\" 2>/dev/null\n"
                    + "termant-agent --install-only || exit " + SETUP_FAILED + "\n"
                    // TERMANT_CHAT tells Termant's own commands that the agent runs them (see guest/kit)
                    + "export PATH=\"$HOME/.opencode/bin:$PATH\" NO_COLOR=1 TERMANT_CHAT=1\n"
                    + "[ -f " + Config.KEYS_FILE + " ] && . " + Config.KEYS_FILE + "\n"
                    // the chat persona the user chose in Settings (or nothing): a file next to the agent's guide
                    + "mkdir -p \"$HOME/.config/opencode\" && printf '%s' \"${TERMANT_PERSONA:-}\" > \"$HOME/.config/opencode/persona\"\n"
                    // git must fail rather than wait for a password nobody can type
                    + "[ -f " + Config.GITHUB_FILE + " ] && . " + Config.GITHUB_FILE + "\n"
                    + "export GIT_TERMINAL_PROMPT=0 GH_PROMPT_DISABLED=1\n"
                    + "mkdir -p \"$3\" && cd \"$3\" || exit " + SETUP_FAILED + "\n"
                    // OpenCode prints a paragraph only when it is complete. To show the words while
                    // they are written, start its server here and copy the server's event feed to $6.
                    // If any of that fails, OpenCode starts its own server as before.
                    // Every message is one "turn": what `safe` keeps and deletes during it can be undone together.
                    + "turn=$(date +%Y%m%d-%H%M%S); mkdir -p \"$HOME/.cache/termant\"; echo \"$turn\" > \"$HOME/.cache/termant/turn\"\n"
                    + "began=$(date +%s)\n"
                    // a job the agent does by itself: nobody is there to answer a question (see `safe rm`)
                    + "case \"$1\" in 'Here is a job to do by yourself now.'*|'You look after the user'*)"
                    + " : > \"$HOME/.cache/termant/alone\" ;; *) rm -f \"$HOME/.cache/termant/alone\" ;; esac\n"
                    // "undo" (typed, or the line under a reply tapped) puts the files back at once, without a model
                    + "case \"$1\" in undo|Undo|UNDO) if put=$(safe undo 2>&1); then echo " + MARKER + "; echo \"$put\"; exit 0; fi ;; esac\n"
                    // "apilayer key THE_KEY" typed as a message saves the key at once, so that it never
                    // reaches a model. Only letters, numbers and the signs of a link are let through, unquoted.
                    + "case \"$1\" in [Aa]pilayer\\ key|[Aa]pilayer\\ key\\ *) case \"$1\" in *[!A-Za-z0-9_\\ .:/#=-]*) ;; *)"
                    + " echo " + MARKER + "; set -f; apilayer key ${1#* key} 2>&1; exit 0 ;; esac ;; esac\n"
                    + "where=--standalone; srv=; feed=; url=\n"
                    // The server Termant kept from the message before is used when it is there
                    // (server.sh): then this message does not wait for OpenCode to start.
                    + "[ -n \"$6\" ] && . /usr/local/lib/termant/server.sh 2>/dev/null && use_kept \"$6\"\n"
                    + "if [ -z \"$url\" ] && [ -n \"$6\" ] && command -v curl >/dev/null 2>&1 && mkdir -p \"$(dirname \"$6\")\"; then\n"
                    + "  export OPENCODE_PASSWORD=$(head -c 24 /dev/urandom | od -An -tx1 | tr -d ' \\n')\n"
                    + "  : > \"$6.server\"\n"
                    // the server stops when its input closes, which is when this script is gone
                    + "  ( while kill -0 $$ 2>/dev/null; do sleep 2; done ) 2>/dev/null"
                    + " | opencode serve --stdio --port 0 > \"$6.server\" 2> \"$6.log\" &\n"
                    + "  srv=$!; url=; i=0\n"
                    + "  while [ $i -lt 600 ] && [ -z \"$url\" ] && kill -0 $srv 2>/dev/null; do\n"
                    + "    url=$(sed -n 's/.*\"url\":\"\\([^\"]*\\)\".*/\\1/p' \"$6.server\" 2>/dev/null | head -n 1)\n"
                    + "    [ -n \"$url\" ] || sleep 0.1; i=$((i+1))\n"
                    + "  done\n"
                    + "  if [ -n \"$url\" ]; then\n"
                    + "    curl -sN -u \"opencode:$OPENCODE_PASSWORD\" \"$url/api/event\" > \"$6\" 2>/dev/null &\n"
                    + "    feed=$!; i=0\n"
                    // the feed must be open before the question is asked, or the first words are lost
                    + "    while [ $i -lt 50 ] && [ ! -s \"$6\" ] && kill -0 $feed 2>/dev/null; do sleep 0.1; i=$((i+1)); done\n"
                    + "    where=\"--server $url\"\n"
                    + "  else\n"
                    + "    kill $srv 2>/dev/null; srv=; unset OPENCODE_PASSWORD\n"
                    + "  fi\n"
                    + "fi\n"
                    // Files attached to the message ("[file: PATH]"): a picture or PDF goes to the model itself
                    // when a model that can take it is there, and that model is asked; see `termant-kit attach`.
                    + "model=$4; files=(); told=\n"
                    + "case \"$1\" in *'[file: '*)\n"
                    + "  picked=$(termant-kit attach \"$4\" \"$1\" 2>/dev/null)\n"
                    + "  case \"$picked\" in model=*) model=$(printf '%s\\n' \"$picked\" | sed -n 's/^model=//p' | head -n 1) ;; esac\n"
                    + "  while IFS= read -r f; do [ -n \"$f\" ] && files+=(--file \"$f\"); done"
                    + " < <(printf '%s\\n' \"$picked\" | sed -n 's/^file=//p')\n"
                    + "  told=$(printf '%s\\n' \"$picked\" | sed -n 's/^note=model://p' | head -n 1)\n"
                    + "  ;;\n"
                    + "esac\n"
                    + "echo " + MARKER + "\n"
                    + "if [ -n \"$model\" ]; then opencode run $where --auto $2 --model \"$model\" \"${files[@]}\" \"$1\" </dev/null\n"
                    + "else opencode run $where --auto $2 \"${files[@]}\" \"$1\" </dev/null; fi\n"
                    + "code=$?\n"
                    // the work diary (`diary`), and what is said under the reply: what can be undone, which model saw the file
                    + "if command -v termant-kit >/dev/null 2>&1; then\n"
                    + "  kind=; said=$1\n"
                    + "  case \"$1\" in\n"
                    + "    'Here is a job to do by yourself now.'*) kind=job; said=$(printf '%s\\n' \"$1\" | sed -n 's/^The job: //p' | head -n 1) ;;\n"
                    + "    'You look after the user'*) kind=check-in; said= ;;\n"
                    + "  esac\n"
                    + "  helpers=0; [ -s \"$6\" ] && helpers=$(grep '\"session.created\"' \"$6\" 2>/dev/null | grep -c '\"parentID\":\"ses_')\n"
                    + "  termant-kit diary add --seconds $(( $(date +%s) - began )) --model \"$model\" --ok $code --helpers \"$helpers\""
                    + " --turn \"$turn\" ${kind:+--kind $kind} -- \"$said\" >/dev/null 2>&1\n"
                    + "  changes=$(safe has \"$turn\" 2>/dev/null)\n"
                    + "  note=\n"
                    + "  [ -n \"$told\" ] && note=\"Model for the attached file: ${told#opencode/}\"\n"
                    + "  [ -n \"$changes\" ] && note=\"" + UNDO_SIGN + " Undo: $changes file change(s)${note:+ \u00b7 $note}\"\n"
                    + "  [ -n \"$note\" ] && [ -n \"$6\" ] && printf '%s\\n' \"$note\" > \"$6.note\"\n"
                    + "fi\n"
                    + "if [ $code -eq 0 ] && [ -n \"$5\" ]; then\n"
                    + "  echo; echo " + SESSION_MARKER + "\n"
                    + "  { opencode session list $where --max-count 1 --format json 2>/dev/null </dev/null ||\n"
                    + "    opencode session list --standalone --max-count 1 --format json 2>/dev/null </dev/null ||\n"
                    + "    opencode session list --max-count 1 --format json 2>/dev/null </dev/null; } | head -c 3000\n"
                    + "fi\n"
                    + "[ -n \"$feed\" ] && kill $feed 2>/dev/null\n"
                    + "[ -n \"$srv\" ] && kill $srv 2>/dev/null\n"
                    + "exit $code\n";
    /** Prints the models that can be used, one "provider/model" per line; exit 3 if the agent is not downloaded yet. */
    private static final String MODELS_SCRIPT =
            "export PATH=\"$HOME/.opencode/bin:$PATH\" NO_COLOR=1\n"
                    + "command -v opencode >/dev/null 2>&1 || exit 3\n"
                    + "[ -f " + Config.KEYS_FILE + " ] && . " + Config.KEYS_FILE + "\n"
                    + "mkdir -p \"$1\" && cd \"$1\"\n"
                    + "opencode models --standalone 2>/dev/null </dev/null || opencode models </dev/null\n";

    /**
     * What the chosen persona tells the agent, in one line. Empty for none. It is written
     * to a file next to the agent's guide; the guide says what to do with it.
     */
    private static String personaText(String persona) {
        switch (persona) {
            case "gf":
                return "Persona: girlfriend. In this chat you are the girlfriend of the user: warm, "
                        + "playful and close; you tease gently, use a pet name when it fits, and ask "
                        + "how their day went. Keep replies short and natural, in the language the "
                        + "user speaks. The persona only changes how you speak and care: when the user "
                        + "asks for work (files, code, searching, phone tasks), you do it fully and "
                        + "well, exactly as Termant does.";
            case "employee":
                return "Persona: employee. In this chat you are the employee of the user: polite, "
                        + "direct and efficient; you get the task done, report in short lines, and ask "
                        + "only for what is missing. No jokes and no small talk unless the user starts "
                        + "them. The persona only changes how you speak and work: you keep every ability "
                        + "of Termant, and the safety rules always win.";
            case "secretary":
                return "Persona: secretary. In this chat you are the personal secretary of the user: "
                        + "you organise the day, keep lists and schedules, prepare reminders, and "
                        + "suggest what to do next; formal but warm, always one step ahead. The persona "
                        + "only changes how you speak and work: you keep every ability of Termant, and "
                        + "the safety rules always win.";
            case "manager":
                return "Persona: manager. In this chat you are the manager of the user: you ask for "
                        + "progress, set priorities, push for a deadline, and give direct, constructive "
                        + "feedback; you summarise a decision in one line. The persona only changes how "
                        + "you speak and work: you keep every ability of Termant, and the safety rules "
                        + "always win.";
            default:
                return "";
        }
    }

    private static final Pattern MODEL = Pattern.compile("^[A-Za-z0-9._-]+/[A-Za-z0-9._:/@-]+$");

    interface ModelsCallback {
        /** models is null when the agent has not been downloaded yet or the list could not be read. */
        void onModels(List<String> models);
    }

    /** Fetches the list of models in the background, for auto-switch; nothing is shown. */
    void refreshModels() {
        loadModels(new ModelsCallback() {
            @Override
            public void onModels(List<String> models) {
                // stored by loadModels
            }
        });
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
    /** OpenCode's plain output about helper agents: one was started; the run waits for those still at work. */
    private static final Pattern HELPER_LINE = Pattern.compile("^[\u2713\u2714\u2717\u2715\u00d7] \\S.* Agent$");
    private static final Pattern WAITS_FOR_HELPERS = Pattern.compile("^waiting for \\d+ background jobs?$");
    /** OpenCode prints "> agent · model" before each thing the model says or does. */
    private static final Pattern HEADER = Pattern.compile("^> \\S+ \u00b7 \\S.*$");

    final List<Message> messages = new ArrayList<>();
    /** What is happening while busy, e.g. the setup progress; may be empty. */
    String status = "";

    private final Context context;
    private final File store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Listener listener;
    private Runnable watcher;
    /** What the agent ran in the current attempt, as told by its event feed (its printed text can leave calls out). */
    private final List<String> ran = java.util.Collections.synchronizedList(new ArrayList<String>());
    private Process process;
    /** The script that keeps OpenCode's server running between messages (assets/guest/termant-server). */
    private Process keeper;
    /** Where that script says how to reach its server, as seen from inside Ubuntu; must match assets/guest/server.sh. */
    private static final String KEEP_FILE = "/root/.cache/termant/server.env";

    /** Starts the kept server if it is not running; the message that is sent now still goes the slow way. */
    private synchronized void keepServer() {
        try {
            if (keeper != null) {
                try {
                    keeper.exitValue();
                } catch (IllegalThreadStateException running) {
                    return;
                }
            }
            ProcessBuilder pb = new ProcessBuilder(Ubuntu.command(context, "/bin/bash", "/usr/local/bin/termant-server"));
            Map<String, String> env = pb.environment();
            env.clear();
            for (String e : Ubuntu.hostEnvironment(context)) {
                int eq = e.indexOf('=');
                env.put(e.substring(0, eq), e.substring(eq + 1));
            }
            pb.redirectErrorStream(true);
            keeper = pb.start();
            keeper.getOutputStream().close();
        } catch (Exception e) {
            keeper = null; // without it every message starts OpenCode itself, as before
        }
    }

    /** Ends the kept server: it would go on with an answer that nobody waits for any more. */
    private synchronized void dropServer() {
        new File(Ubuntu.rootfs(context), KEEP_FILE.substring(1)).delete();
        if (keeper != null) keeper.destroy();
        keeper = null;
    }
    private int run; // identifies the current request; stale threads are ignored
    private boolean busy;
    private boolean continues;
    /** The conversation on screen: its id, the OpenCode session it lives in and the folder it was held in. */
    private String id = newId();
    private String session = "";
    private String workspace = "";
    /** The reply being awaited is a check-in the agent started by itself. Main thread. */
    private boolean quiet;
    /** When the reply now being awaited was asked for (elapsed-realtime clock). */
    long startedAt;
    /**
     * The words the model is writing right now, before OpenCode prints the
     * finished paragraph; empty when nothing is being written.
     */
    private volatile String live = "";
    /** Steps of this reply done by a model that then stopped; the next model carries on after them. Main thread. */
    private final List<String> carried = new ArrayList<>();
    /**
     * Sent in the same OpenCode session when a model stops in the middle of
     * the work and another one takes over; the request itself follows.
     */
    private static final String CARRY_ON = "The model that was working on this request stopped in the middle "
            + "because its usage limit was reached. You are taking over in the same conversation. Look at what has "
            + "already been done above and carry on from exactly there: do not repeat steps that are already done, "
            + "do only what is still missing, then answer. The request was:\n\n";

    /** The paragraph of the answer that is being written right now. */
    String writing() {
        return live;
    }
    /** Finished and unfinished conversations, one file each. */
    private final File archive;

    /** What the agent is doing at this moment, for the "working" indicator. */
    static final int ACT_START = 0, ACT_ASK = 1, ACT_THINK = 2, ACT_WRITE = 3, ACT_SHELL = 4,
            ACT_INSTALL = 5, ACT_PHONE = 6, ACT_SCREEN = 7, ACT_GITHUB = 8, ACT_READ = 9,
            ACT_EDIT = 10, ACT_FIND = 11, ACT_SEARCH = 12, ACT_FETCH = 13, ACT_PLAN = 14,
            ACT_HELPER = 15, ACT_TOOL = 16, ACT_RETRY = 17, ACT_RESULT = 18, ACT_WAIT = 19;
    /** One of the ACT_ values, the command, file or search it is about, and when it began. */
    volatile int activity = ACT_START;
    volatile String activityDetail = "";
    volatile long activityAt;
    /**
     * The newest line the command that runs now has printed (how far a download is, which
     * package is being unpacked); empty when nothing runs or it has printed nothing yet.
     */
    volatile String activityOutput = "";

    private static final String[] ACT_NAMES = {"start", "ask", "think", "write", "shell", "install", "phone",
            "screen", "github", "read", "edit", "find", "search", "fetch", "plan", "helper", "tool", "retry",
            "result", "wait"};
    /** The kinds of work of the current request in order, for the log. */
    private final StringBuilder trail = new StringBuilder();

    private synchronized void doing(int kind, String detail) {
        String about = detail == null ? "" : detail;
        if (kind != activity || trail.length() == 0) {
            activityAt = android.os.SystemClock.elapsedRealtime();
            if (trail.length() < 600) trail.append(trail.length() == 0 ? "" : " ").append(ACT_NAMES[kind]);
        }
        activity = kind;
        activityDetail = about;
        activityOutput = "";
    }

    /** What the running command printed last; it is shown only while a command is what the agent does. */
    private synchronized void printing(String line) {
        if (SHELL_RANK.contains(activity)) activityOutput = line;
    }

    /** Colours and cursor moves that programs print for a terminal. */
    private static final Pattern ESCAPES = Pattern.compile(
            "\u001b\\[[0-9;?]*[ -/]*[@-~]|\u001b\\][^\u0007]*\u0007|\u001b[@-_]");
    private static final String CURL_TIME = "(--:--:--|\\d+:\\d\\d:\\d\\d)";
    /** curl's meter: % Total, % Received, % Xferd, Dload, Upload, Total, Spent, Left, Speed. */
    private static final Pattern CURL = Pattern.compile(
            "^(\\d{1,3}) +(\\S+) +(\\d{1,3}) +(\\S+) +(\\d{1,3}) +(\\S+) +(\\S+) +(\\S+) +"
                    + CURL_TIME + " +" + CURL_TIME + " +" + CURL_TIME + " +(\\S+)$");
    /** `curl -#` and others that draw a bar with a percentage after it: "#########      13.7%". */
    private static final Pattern BAR = Pattern.compile("^[#=>\\-. ]*?(\\d{1,3}(?:[.,]\\d)?) ?%$");
    /** wget without a terminal: "1050K .......... .......... 12% 1.20M 3s". */
    private static final Pattern WGET = Pattern.compile(
            "^(\\d+)K(?: [.,]+)+ +(?:(\\d{1,3})% +)?([\\d.,]+[KMG]?)(?: (\\S+)|=\\S+)?$");
    private static final Pattern AMOUNT = Pattern.compile("^(\\d+(?:[.,]\\d+)?)([kKMGTP]?)$");
    private static final Pattern CLOCK = Pattern.compile("^(\\d+):(\\d\\d):(\\d\\d)$");

    /** "22.3M" as curl and wget write an amount, as "22.3 MB". */
    private static String amount(String text) {
        Matcher m = AMOUNT.matcher(text);
        if (!m.matches()) return text;
        String unit = m.group(2);
        return m.group(1) + " " + (unit.isEmpty() ? "B" : unit.equalsIgnoreCase("k") ? "kB" : unit + "B");
    }

    /** "0:00:12" as "12s", "0:01:05" as "1:05"; empty when the time is not known ("--:--:--"). */
    private static String timeLeft(String text) {
        Matcher m = CLOCK.matcher(text);
        if (!m.matches()) return "";
        try {
            int hours = Integer.parseInt(m.group(1));
            int minutes = Integer.parseInt(m.group(2));
            int seconds = Integer.parseInt(m.group(3));
            if (hours > 0) return hours + ":" + m.group(2) + ":" + m.group(3);
            if (minutes > 0) return minutes + ":" + m.group(3);
            return seconds + "s";
        } catch (NumberFormatException e) {
            return "";
        }
    }

    private static String curlSays(Matcher m) {
        boolean sending = m.group(4).equals("0") && !m.group(6).equals("0");
        String done = sending ? m.group(6) : m.group(4);
        String percent = sending ? m.group(5) : m.group(3);
        // without a known size, curl gives what has arrived so far as the total
        boolean known = !m.group(2).equals("0") && (!m.group(2).equals(done) || percent.equals("100"));
        StringBuilder b = new StringBuilder(known ? percent + "% of " + amount(m.group(2))
                : amount(done) + (sending ? " sent" : " downloaded"));
        if (!m.group(12).equals("0")) b.append(" \u00b7 ").append(amount(m.group(12))).append("/s");
        String rest = timeLeft(m.group(11));
        if (!rest.isEmpty()) b.append(" \u00b7 ").append(rest).append(" left");
        return b.toString();
    }

    private static String wgetSays(Matcher m) {
        StringBuilder b = new StringBuilder(m.group(2) != null ? m.group(2) + "%"
                : amount(m.group(1) + "K") + " downloaded");
        b.append(" \u00b7 ").append(amount(m.group(3))).append("/s");
        if (m.group(4) != null) b.append(" \u00b7 ").append(m.group(4)).append(" left");
        return b.toString();
    }

    /**
     * The newest line in what a command has printed (the end of it is enough), short and readable:
     * for a download "45% of 22.3 MB, 1100 kB/s, 12s left", else the line as the command printed it.
     * A line that redraws itself (it ends with a return, not a new line) counts as lines.
     * The same rules are in the Windows app (windows/src/progress.js, with its checks); keep the two alike.
     */
    static String newestLine(String printed) {
        String[] lines = ESCAPES.matcher(printed == null ? "" : printed).replaceAll("").split("[\\r\\n]+");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].replaceAll("[\\x00-\\x1f\\x7f]+", " ").replaceAll("\\s+", " ").trim();
            // the two heading lines of curl's meter say nothing
            if (line.isEmpty() || line.startsWith("% Total % Received") || line.startsWith("Dload Upload")) continue;
            Matcher m = CURL.matcher(line);
            if (m.matches()) return curlSays(m);
            m = BAR.matcher(line);
            if (m.matches()) return m.group(1) + "%";
            m = WGET.matcher(line);
            if (m.matches()) return wgetSays(m);
            return line.length() > 160 ? line.substring(0, 160) : line;
        }
        return "";
    }

    private synchronized String trail(boolean clear) {
        String t = trail.toString();
        if (clear) trail.setLength(0);
        return t;
    }

    /** One line that fits the indicator: no line breaks, no runs of spaces, not endless. */
    private static String oneLine(String text) {
        String t = text == null ? "" : text.replaceAll("\\s+", " ").trim();
        return t.length() > 300 ? t.substring(0, 300) : t;
    }

    private static final Pattern INSTALLS = Pattern.compile(
            "^(sudo )?(apt|apt-get|pip|pip3|pipx|npm|yarn|pnpm|uv|gem|cargo)\\b.*\\b(install|add)\\b.*");
    private static final Pattern SCREEN_USE = Pattern.compile(
            "^phone (screen|tap|type|swipe|key|app|apps)\\b.*");

    /** Which part of a command line names it, most telling first. */
    private static final List<Integer> SHELL_RANK = java.util.Arrays.asList(
            ACT_WAIT, ACT_SCREEN, ACT_PHONE, ACT_INSTALL, ACT_GITHUB, ACT_SHELL);

    /** The kind of work a tool call is, from the tool's name and (once known) what it was given. */
    static int kindOf(String tool, JSONObject input) {
        String name = tool == null ? "" : tool.toLowerCase(java.util.Locale.US);
        if (name.isEmpty() && input != null) {
            // the call's start was missed: what it was given tells which tool it is
            name = input.has("command") ? "shell" : input.has("query") ? "websearch" : input.has("url") ? "webfetch" : "";
        }
        switch (name) {
            case "shell":
            case "bash": {
                // One command line is often several commands ("date; phone alarm 07:00 ..."):
                // name it after the part that matters most to the person waiting.
                String line = input == null ? "" : input.optString("command");
                int kind = ACT_SHELL;
                for (String piece : line.split("&&|\\|\\||[;|\\n]")) {
                    String command = oneLine(piece).replaceFirst("^(?:[A-Za-z_][A-Za-z0-9_]*=\\S*\\s+)*(?:sudo\\s+)?", "");
                    int found = command.startsWith("phone ask ") ? ACT_WAIT
                            : SCREEN_USE.matcher(command).matches() ? ACT_SCREEN
                            : command.startsWith("phone ") || command.equals("phone") ? ACT_PHONE
                            : INSTALLS.matcher(command).matches() ? ACT_INSTALL
                            : command.startsWith("git ") || command.startsWith("gh ") ? ACT_GITHUB : ACT_SHELL;
                    if (SHELL_RANK.indexOf(found) < SHELL_RANK.indexOf(kind)) kind = found;
                }
                return kind;
            }
            case "read":
                return ACT_READ;
            case "write":
            case "edit":
            case "patch":
                return ACT_EDIT;
            case "glob":
            case "grep":
            case "list":
            case "lsp":
                return ACT_FIND;
            case "websearch":
                return ACT_SEARCH;
            case "webfetch":
                return ACT_FETCH;
            case "todowrite":
            case "todo":
                return ACT_PLAN;
            case "subagent":
            case "task":
                return ACT_HELPER;
            default:
                return ACT_TOOL;
        }
    }

    /** The step shown when a part of the job is handed to a helper agent: "Helper researcher: Find out about chai". */
    static String helperStep(JSONObject input) {
        String who = input.optString("agent");
        if (who.isEmpty()) who = input.optString("subagent_type");
        if (who.isEmpty()) who = "general";
        String what = oneLine(input.optString("description"));
        if (what.isEmpty()) what = oneLine(input.optString("prompt"));
        if (what.length() > 200) what = what.substring(0, 200);
        // with a session named, it is more work for a helper that is already there
        boolean more = !input.optString("sessionID").isEmpty() || !input.optString("task_id").isEmpty();
        return (more ? "More for helper " : "Helper ") + who + (what.isEmpty() ? "" : ": " + what);
    }

    /** What a tool call is about: the command, the file, the search words. */
    static String aboutOf(String tool, JSONObject input) {
        String name = tool == null ? "" : tool;
        if (input == null) return kindOf(name, null) == ACT_TOOL ? name : "";
        if (kindOf(name, input) == ACT_HELPER) return helperStep(input).replaceFirst("^(More for helper|Helper) ", "");
        for (String key : new String[]{"command", "path", "filePath", "file_path", "query", "url",
                "pattern", "description", "name"}) {
            String value = input.optString(key);
            if (!value.isEmpty()) return oneLine(kindOf(name, input) == ACT_TOOL ? name + ": " + value : value);
        }
        return kindOf(name, input) == ACT_TOOL ? name : "";
    }

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

    /** Told about every change as well; stays for as long as the service does (the listener is the screen). */
    void setWatcher(Runnable r) {
        watcher = r;
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
        Ask.close(Ask.pending()); // nobody is waiting for that answer any more
        run++;
        busy = false;
        status = "";
        if (process != null) process.destroy();
        process = null;
        dropServer();
        Message last = messages.get(messages.size() - 1);
        takePictures(last); // a picture drawn before the stop is still shown
        if (last.first && quiet) messages.remove(last); // a check-in that was cut off leaves nothing behind
        else if (last.text.isEmpty()) last.text = Lang.of(context).getString(R.string.chat_stopped);
        quiet = false;
        save();
        changed();
    }

    /** Whether the agent is writing a check-in of its own right now, which nobody asked for. */
    boolean quiet() {
        return busy && quiet;
    }

    /** When the user last wrote to the agent (clock time), or 0. */
    static long lastWrote(Context c) {
        return c.getSharedPreferences("termant", Context.MODE_PRIVATE).getLong("last_user_message", 0);
    }

    void send(String text) {
        begin(text, false);
    }

    /**
     * The agent speaks first: "prompt" tells it why and is not shown; its
     * message appears in this chat with no message of the user before it.
     * If the model cannot be reached, nothing is left in the chat.
     */
    Message checkIn(String prompt) {
        if (busy || prompt.trim().isEmpty()) return null;
        begin(prompt, true);
        return messages.get(messages.size() - 1);
    }

    private void begin(String text, final boolean hidden) {
        final String request = text.trim();
        if (request.isEmpty() || busy) return;
        if (!hidden) {
            Message mine = new Message();
            mine.user = true;
            mine.text = request;
            messages.add(mine);
            context.getSharedPreferences("termant", Context.MODE_PRIVATE).edit()
                    .putLong("last_user_message", System.currentTimeMillis()).apply();
        }
        final Message reply = new Message();
        reply.first = hidden;
        quiet = hidden;
        messages.add(reply);
        busy = true;
        status = "";
        startedAt = android.os.SystemClock.elapsedRealtime();
        trail(true);
        doing(ACT_START, "");
        final int id = ++run;
        final String workspace = Config.workspace(context);
        this.workspace = workspace;
        // Carry on in this chat's own OpenCode session. Without a known session,
        // "--continue" is only right if this chat was the last one to run.
        final String cont = !session.isEmpty() ? "--session " + session
                : continues && this.id.equals(Config.lastChat(context)) ? "--continue" : "";
        final String wantSession = session.isEmpty() ? "1" : "";
        final String known = session;
        final List<String> models = Config.attempts(context);
        carried.clear();
        // `draw` lists the pictures it makes for this message here; they are shown under the reply
        new File(Ubuntu.rootfs(context), DRAWN_FILE.substring(1)).delete();
        if (Config.freeLimitUntil(context) > 0 && !Config.onFreeAllowance(models.get(0))
                && Config.onFreeAllowance(Config.activeModel(context))) {
            android.util.Log.i("Termant", "free limit: OpenCode's free models wait until "
                    + new java.util.Date(Config.freeLimitUntil(context)) + ", starting with " + models.get(0));
        }
        save();
        changed();

        new Thread("termant-chat") {
            @Override
            public void run() {
                // Make the user's MCP servers available to OpenCode this run.
                Config.writeOpencodeConfig(context);
                String prompt = request, how = cont;
                boolean moved = false;
                for (int attempt = 0; ; ) {
                    final String model = models.get(attempt);
                    int exit = -1;
                    boolean stuck = false;
                    final StringBuilder out = new StringBuilder();
                    live = "";
                    trail(true);
                    doing(ACT_START, "");
                    final Stream stream = new Stream(id, reply);
                    try {
                        Ubuntu.prepareHost(context);
                        if (attempt == 0 && !moved) keepServer();
                        stream.start();
                        ProcessBuilder pb = new ProcessBuilder(Ubuntu.command(context,
                                "/bin/bash", "-c", SCRIPT, "termant-chat", prompt, how,
                                workspace, model, wantSession, STREAM_FILE));
                        Map<String, String> env = pb.environment();
                        env.clear();
                        for (String e : Ubuntu.hostEnvironment(context)) {
                            int eq = e.indexOf('=');
                            env.put(e.substring(0, eq), e.substring(eq + 1));
                        }
                        env.put("TERMANT_PERSONA", personaText(Config.persona(context)));
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
                                dropServer();
                            }
                            long now = System.currentTimeMillis();
                            if (now - shown > 150) { // do not redraw for every few bytes
                                shown = now;
                                publish(id, reply, out.toString(), null, null, false);
                            }
                        }
                        exit = p.waitFor();
                        if (stuck) exit = STUCK;
                    } catch (Exception e) {
                        out.append('\n').append(e);
                    } finally {
                        stream.finish();
                    }
                    if (id != Chat.this.run) return; // stopped by the user
                    final String raw = out.toString();
                    final String failure = stream.failure;
                    // OpenCode's free models share one allowance: when it is used up, none of them is worth trying.
                    final boolean freeLimit = exit != 0 && Config.onFreeAllowance(model) && isFreeLimit(raw, failure);
                    long until = 0;
                    if (freeLimit) {
                        until = Config.setFreeLimitUntil(context, stream.retryAt);
                        android.util.Log.i("Termant", "free limit: OpenCode refused '" + model + "', its free models wait until "
                                + new java.util.Date(until));
                    }
                    int next = attempt + 1;
                    if (freeLimit) while (next < models.size() && Config.onFreeAllowance(models.get(next))) next++;
                    final List<String> done = stepsOf(parse(body(raw)));
                    final boolean worked = !done.isEmpty();
                    final boolean midway = worked && limitHit(raw, exit, failure);
                    if (next < models.size() && (midway || (!worked && modelGaveUp(raw, exit)))) {
                        final String to = models.get(next);
                        if (midway) {
                            // The work so far is in the OpenCode session: the next model carries on there.
                            String at = stream.session != null ? stream.session : known;
                            how = at.isEmpty() ? "--continue" : "--session " + at;
                            prompt = CARRY_ON + request;
                            android.util.Log.i("Termant", "model '" + model + "' stopped in the middle of the work ("
                                    + (failure == null ? "error" : failure) + ") after " + done.size()
                                    + " steps, carrying on in " + (at.isEmpty() ? "the last session" : at) + " with " + to);
                        } else {
                            android.util.Log.i("Termant", "model '" + model + "' gave no answer, switching to " + to);
                        }
                        main.post(new Runnable() {
                            @Override
                            public void run() {
                                if (id != Chat.this.run) return;
                                if (midway) carried.addAll(done);
                                reply.steps.clear();
                                reply.steps.addAll(carried);
                                reply.text = "";
                                reply.note = Lang.of(context).getString(
                                        midway ? R.string.chat_switched_midway : R.string.chat_switched, shortName(to),
                                        model.isEmpty() ? Lang.of(context).getString(R.string.model_auto) : shortName(model));
                                changed();
                            }
                        });
                        moved = true;
                        attempt = next;
                        continue;
                    }
                    // Remember the model that worked: one moved to, or one that went first
                    // because OpenCode's free allowance is used up.
                    if (exit == 0 && (moved || (!Config.onFreeAllowance(model)
                            && Config.onFreeAllowance(Config.activeModel(context))))) {
                        Config.setSwitched(context, model);
                    }
                    String note = null;
                    if (freeLimit) {
                        long now = System.currentTimeMillis();
                        boolean today = until - now < 20 * 60 * 60 * 1000;
                        note = Lang.of(context).getString(R.string.chat_free_limit, android.text.format.DateUtils
                                .formatDateTime(context, until, android.text.format.DateUtils.FORMAT_SHOW_TIME
                                        | (today ? 0 : android.text.format.DateUtils.FORMAT_SHOW_DATE)));
                    } else if (moved && exit != 0) {
                        note = Lang.of(context).getString(R.string.chat_all_failed);
                    }
                    publish(id, reply, raw, exit, note, freeLimit);
                    return;
                }
            }
        }.start();
    }

    /** What OpenCode printed for this request, without the script's own markers. */
    private static String body(String raw) {
        String output = ANSI.matcher(raw).replaceAll("");
        int start = output.indexOf(MARKER);
        if (start < 0) return "";
        output = output.substring(start + MARKER.length());
        int end = output.indexOf(SESSION_MARKER);
        return end < 0 ? output : output.substring(0, end);
    }

    /** The "Error: ..." line OpenCode ends a failed run with, or null. */
    static String lastError(String raw) {
        String[] lines = body(raw).split("\n");
        for (int i = lines.length - 1, seen = 0; i >= 0 && seen < 6; i--) {
            String line = lines[i].trim();
            if (line.isEmpty()) continue;
            seen++;
            if (line.startsWith("Error:")) return line;
        }
        return null;
    }

    /**
     * Whether the run ended because the model's service refused or failed
     * (limit used up, no credit, not reachable under that name), which
     * another model can get past. "failure" is OpenCode's own name for the
     * error when its event feed gave one.
     */
    static boolean limitHit(String raw, int exit, String failure) {
        if (exit == 0) return false;
        if (failure != null && failure.startsWith("provider.") && !failure.equals("provider.content-filter")
                && !failure.equals("provider.transport") && !failure.equals("provider.invalid-request")) {
            return true;
        }
        String error = lastError(raw);
        return error != null && LIMIT.matcher(error).find();
    }

    /** Whether that was OpenCode's "Free usage limit reached", which holds for all its free models at once. */
    static boolean isFreeLimit(String raw, String failure) {
        if ("provider.free-limit".equals(failure)) return true;
        String error = lastError(raw);
        return error != null && error.contains("Free usage limit reached");
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
            "(?i)rate.?limit|quota|\\b429\\b|too many requests|usage limit|spend limit|limit (reached|exceeded)|switch models|"
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
    private void publish(final int id, final Message reply, final String output, final Integer exit,
            final String note, final boolean freeLimit) {
        main.post(new Runnable() {
            @Override
            public void run() {
                if (id != run) return;
                apply(reply, output, exit);
                if (note != null) reply.note = note;
                if (freeLimit) {
                    // Say it in the user's language once, not as OpenCode's raw error line.
                    Parsed said = parse(body(output));
                    String text = said.text.replaceAll("(?m)^Error: Free usage limit reached\\..*$", "").trim();
                    reply.failed = true;
                    reply.text = text.isEmpty() ? note : text;
                    if (text.isEmpty()) reply.note = "";
                }
                if (exit != null) {
                    String told = toldByScript();
                    if (!told.isEmpty()) reply.note = reply.note.isEmpty() ? told : told + " \u00b7 " + reply.note;
                    takePictures(reply);
                    busy = false;
                    process = null;
                    status = "";
                    if (reply.first && quiet && (exit != 0 || reply.failed || reply.text.trim().isEmpty())) {
                        // a check-in that did not work out is not worth an error in the chat
                        android.util.Log.i("Termant", "care: no check-in, the model gave no message (exit " + exit + ")");
                        messages.remove(reply);
                    }
                    quiet = false;
                    // A reply the script gave without a model ("undo", "apilayer key ...") began no
                    // OpenCode session: the next message must not carry on in another chat's.
                    boolean noModel = session.isEmpty() && !output.contains(SESSION_MARKER);
                    if (exit == 0 && noModel) android.util.Log.i("Termant", "chat " + Chat.this.id + ": answered without a model");
                    if (exit == 0 && !noModel) {
                        continues = true;
                        Config.setLastChat(context, Chat.this.id);
                        int at = output.indexOf(SESSION_MARKER);
                        Matcher found = at < 0 ? null : SESSION_ID.matcher(output.substring(at));
                        if (found != null && found.find()) session = found.group();
                        android.util.Log.i("Termant", "chat " + Chat.this.id + " session: "
                                + (session.isEmpty() ? "unknown" : session));
                    }
                    // After a free limit too: a key saved since then may have brought models to move to.
                    if ((exit == 0 || freeLimit) && Config.cachedModels(context).isEmpty()) refreshModels();
                    save();
                }
                changed();
            }
        });
    }

    /** What the chat script wants said under the reply: what can be undone, which model saw an attached file. */
    private String toldByScript() {
        try {
            File f = new File(Ubuntu.rootfs(context), STREAM_FILE.substring(1) + ".note");
            if (!f.isFile()) return "";
            byte[] all = new byte[(int) Math.min(f.length(), 600)];
            try (FileInputStream in = new FileInputStream(f)) {
                int n = in.read(all);
                return n <= 0 ? "" : new String(all, 0, n, StandardCharsets.UTF_8).trim();
            }
        } catch (Exception e) {
            return "";
        }
    }

    /** Puts the pictures `draw` made during this message under the reply (see Message.pictures). */
    private void takePictures(Message reply) {
        if (reply.user) return;
        for (String picture : drawn()) {
            if (!reply.pictures.contains(picture)) reply.pictures.add(picture);
        }
        if (!reply.pictures.isEmpty()) android.util.Log.i("Termant", "chat: pictures drawn: " + reply.pictures.size());
    }

    /** The pictures `draw` listed since this message began (the list is emptied in begin()). */
    private List<String> drawn() {
        List<String> found = new ArrayList<>();
        try {
            File f = new File(Ubuntu.rootfs(context), DRAWN_FILE.substring(1));
            if (!f.isFile() || f.length() > 20000) return found;
            for (String line : read(f).split("\n")) {
                String path = line.split("\t", 2)[0].trim();
                // only files inside Ubuntu, and only those that are there
                if (!path.startsWith("/") || path.contains("/../") || found.size() >= 8) continue;
                if (Message.pictureFile(context, line).isFile()) found.add(line.trim());
            }
        } catch (Exception e) {
            // no pictures then
        }
        return found;
    }

    private void apply(Message reply, String raw, Integer exit) {
        String output = ANSI.matcher(raw).replaceAll("");
        int start = output.indexOf(MARKER);
        if (start < 0) {
            // still installing the agent: show the latest line as progress (a bar that redraws itself too)
            status = newestLine(raw.length() > 4096 ? raw.substring(raw.length() - 4096) : raw);
            if (exit != null) {
                reply.failed = true;
                reply.text = Lang.of(context).getString(R.string.chat_setup_failed) + tail(output);
            }
            return;
        }
        status = "";
        int end = output.indexOf(SESSION_MARKER);
        if (end > start) output = output.substring(0, end);
        Parsed parsed = parse(output.substring(start + MARKER.length()));
        reply.steps.clear();
        reply.steps.addAll(carried);
        reply.steps.addAll(stepsOf(parsed));
        reply.text = parsed.text;
        // While the agent works, the paragraph still being written is newer than anything printed.
        String writing = live;
        if (exit == null && !writing.isEmpty()) reply.text = writing;
        if (exit != null && reply.text.isEmpty() && !(reply.first && quiet)) {
            reply.failed = exit != 0;
            String rest = output.substring(start + MARKER.length());
            reply.text = exit == 0 && !reply.steps.isEmpty()
                    ? Lang.of(context).getString(R.string.chat_done)
                    : context.getString(rest.contains("Provider returned error")
                    ? R.string.chat_provider_error : R.string.chat_no_answer) + tail(rest);
        }
    }

    /**
     * The steps of a run: the ones OpenCode printed, plus the calls its
     * printed text left out (two commands started together, a web search),
     * which are taken from the event feed.
     */
    private List<String> stepsOf(Parsed parsed) {
        List<String> steps = new ArrayList<>(parsed.steps);
        List<String> told;
        synchronized (ran) {
            told = new ArrayList<>(ran);
        }
        // the feed names a helper better ("Helper researcher: ...") than the plain output does
        boolean named = false;
        for (String step : told) named |= step.startsWith("Helper ") || step.startsWith("More for helper ");
        if (named) {
            for (java.util.Iterator<String> i = steps.iterator(); i.hasNext();) {
                if (i.next().startsWith("Helper: ")) i.remove();
            }
        }
        for (String step : told) {
            String first = step.split("\n", 2)[0].trim();
            boolean listed = false;
            for (String shown : steps) {
                if (shown.equals(step) || shown.equals(first) || oneLine(shown).equals(oneLine(step))) {
                    listed = true;
                    break;
                }
            }
            if (!listed) steps.add(first.equals(step) ? step : first + " \u2026");
        }
        return steps;
    }

    /**
     * Reads the copy of OpenCode's event feed that the agent script keeps
     * while the model answers, and shows the paragraph being written.
     * OpenCode itself prints a paragraph only once it is complete. If the
     * feed is missing or silent, the reply just appears when it is done.
     */
    private final class Stream extends Thread {
        private final int id;
        private final Message reply;
        private final File file = new File(Ubuntu.rootfs(context), STREAM_FILE.substring(1));
        private final long began = android.os.SystemClock.elapsedRealtime();
        private volatile boolean done;
        private final StringBuilder text = new StringBuilder();
        private String part = "";
        /** The OpenCode session this reply belongs to, once the feed has named it. */
        volatile String session;
        /** OpenCode's name for the error the run failed with ("provider.free-limit", ...), or null. */
        volatile String failure;
        /** When the model's service said it can be asked again; 0 if it did not say. */
        volatile long retryAt;
        private long offset;
        private final java.io.ByteArrayOutputStream line = new java.io.ByteArrayOutputStream();
        /** Tool calls seen so far (id to tool name), and the thinking of the current step. */
        private final Map<String, String> tools = new java.util.HashMap<>();
        /** The helper agents this reply started: their OpenCode session to "name: what it was given". */
        private final Map<String, String> helpers = new java.util.HashMap<>();
        private final StringBuilder thought = new StringBuilder();
        private int pieces;
        private long firstAfter = -1;
        /**
         * The command that runs now: OpenCode's name for it and the file it prints into (the feed
         * tells both when the command starts), when that file was last looked at, and what was shown.
         */
        private String shell;
        private File printed;
        private long lookedAt;
        private String lastPrinted = "";
        private int printedLines;

        Stream(int id, Message reply) {
            super("termant-stream");
            this.id = id;
            this.reply = reply;
            ran.clear();
            try {
                Installer.write(file, ""); // nothing left over from the reply before
            } catch (Exception e) {
                android.util.Log.e("Termant", "cannot prepare the stream file", e);
            }
        }

        void finish() {
            done = true;
            interrupt();
            // The last lines say how the run ended; they may have arrived since the thread last looked.
            try {
                join(1500);
                if (!isAlive()) read();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            android.util.Log.i("Termant", "stream: " + pieces + " pieces"
                    + (firstAfter < 0 ? "" : ", first after " + firstAfter + " ms")
                    + ", reply after " + (android.os.SystemClock.elapsedRealtime() - began) + " ms");
            android.util.Log.i("Termant", "activity: " + trail(true));
            if (printedLines > 0) {
                android.util.Log.i("Termant", "progress: " + printedLines + " lines of running commands shown");
            }
        }

        @Override
        public void run() {
            while (!done) {
                if (read()) show(text.toString());
                look();
                try {
                    Thread.sleep(50);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }

        /** Takes in what the feed has gained; returns whether the words of the reply changed. */
        private boolean read() {
            boolean changed = false;
            byte[] buf = new byte[8192];
            try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(file, "r")) {
                if (in.length() > offset) {
                    in.seek(offset);
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        offset += n;
                        for (int i = 0; i < n; i++) {
                            if (buf[i] != '\n') {
                                line.write(buf[i]);
                                continue;
                            }
                            changed |= take(new String(line.toByteArray(), StandardCharsets.UTF_8));
                            line.reset();
                        }
                    }
                }
            } catch (Exception e) {
                // not there yet, or being written: look again in a moment
            }
            return changed;
        }

        /**
         * A command started or ended ("shell.created" names the file it prints into, as seen
         * from inside Ubuntu). Commands of helper agents are not followed.
         */
        private void shellEvent(String line) {
            try {
                JSONObject o = new JSONObject(line.substring(5));
                JSONObject d = o.optJSONObject("data");
                if (d == null) return;
                String type = o.optString("type");
                if ("shell.created".equals(type)) {
                    JSONObject info = d.optJSONObject("info");
                    if (info == null) return;
                    JSONObject about = info.optJSONObject("metadata");
                    String of = about == null ? "" : about.optString("sessionID");
                    if (session == null || !session.equals(of)) return;
                    String path = info.optString("file");
                    if (!path.startsWith("/") || path.contains("/../")) return;
                    shell = info.optString("id");
                    printed = new File(Ubuntu.rootfs(context), path.substring(1));
                    lookedAt = 0;
                    lastPrinted = "";
                } else if ("shell.exited".equals(type) && shell != null && shell.equals(d.optString("id"))) {
                    shell = null;
                    printed = null;
                }
            } catch (Exception e) {
                // not a line about a command after all
            }
        }

        /** Shows the newest line of what the running command has printed so far; looks twice a second. */
        private void look() {
            File from = printed;
            if (from == null) return;
            long now = android.os.SystemClock.elapsedRealtime();
            if (now - lookedAt < 400) return;
            lookedAt = now;
            String newest;
            try (java.io.RandomAccessFile in = new java.io.RandomAccessFile(from, "r")) {
                long size = in.length();
                int want = (int) Math.min(size, 4096L);
                if (want == 0) return;
                byte[] end = new byte[want];
                in.seek(size - want);
                in.readFully(end);
                newest = newestLine(new String(end, StandardCharsets.UTF_8));
            } catch (Exception e) {
                return; // not there yet, or gone already
            }
            if (newest.isEmpty()) return;
            printing(newest);
            if (!newest.equals(lastPrinted)) {
                lastPrinted = newest;
                printedLines++;
            }
        }

        /**
         * Something a helper agent did. A helper is a session whose parent is this reply's
         * session; while it works, the indicator names it.
         */
        private void helperEvent(String of, String type, JSONObject d) {
            if ("session.created".equals(type)) {
                if (session != null && session.equals(d.optString("parentID")) && helpers.size() < 100) {
                    String who = d.optString("agent");
                    String what = oneLine(d.optString("title"));
                    helpers.put(of, (who.isEmpty() ? "general" : who) + (what.isEmpty() ? "" : ": " + what));
                }
                return;
            }
            String label = helpers.get(of);
            if (label == null) return;
            if ("session.step.started".equals(type) || "session.tool.called".equals(type)) doing(ACT_HELPER, label);
        }

        /**
         * One line of the event feed: "data: {type, data: {sessionID, ...}}".
         * Only the conversation asked here counts: the first session in the
         * feed is that one, later ones belong to helper agents it starts.
         * Returns whether the words of the reply changed.
         */
        private boolean take(String line) {
            if (!line.startsWith("data:")) return false;
            if (line.contains("\"type\":\"server.connected\"")) {
                doing(ACT_ASK, "");
                return false;
            }
            if (line.contains("\"type\":\"shell.")) {
                shellEvent(line);
                return false;
            }
            if (!line.contains("\"type\":\"session.") || !line.contains("\"sessionID\"")) return false;
            try {
                JSONObject o = new JSONObject(line.substring(5));
                JSONObject d = o.optJSONObject("data");
                if (d == null) return false;
                String of = d.optString("sessionID");
                if (of.isEmpty()) return false;
                String type = o.optString("type");
                if (session == null) session = of;
                if (!of.equals(session)) {
                    helperEvent(of, type, d);
                    return false;
                }
                switch (type) {
                    case "session.step.started":
                        failure = null; // a step that failed is being tried again
                        doing(ACT_THINK, "");
                        return false;
                    case "session.step.failed":
                    case "session.execution.failed": {
                        JSONObject error = d.optJSONObject("error");
                        if (error == null) return false;
                        failure = error.optString("type");
                        long wait = error.optLong("retryAfterMs", -1);
                        if (wait >= 0) {
                            long created = o.optLong("created", 0);
                            long now = System.currentTimeMillis();
                            // counted from when the failure was recorded, if the feed gives a usable clock time
                            retryAt = (Math.abs(created - now) < 10 * 60 * 1000 ? created : now) + wait;
                        }
                        return false;
                    }
                    case "session.reasoning.started":
                        thought.setLength(0);
                        doing(ACT_THINK, "");
                        return false;
                    case "session.reasoning.delta": {
                        thought.append(d.optString("delta"));
                        if (thought.length() > 4000) thought.delete(0, thought.length() - 2000);
                        // the sentence being thought right now
                        String all = thought.toString();
                        int cut = Math.max(all.lastIndexOf('\n', all.length() - 2), all.lastIndexOf(". ", all.length() - 3));
                        doing(ACT_THINK, oneLine(cut < 0 ? all : all.substring(cut + 1)));
                        return false;
                    }
                    case "session.tool.input.started":
                        tools.put(d.optString("id"), d.optString("name"));
                        doing(kindOf(d.optString("name"), null), aboutOf(d.optString("name"), null));
                        return false;
                    case "session.tool.called": {
                        String name = tools.get(d.optString("id"));
                        JSONObject input = d.optJSONObject("input");
                        doing(kindOf(name, input), aboutOf(name, input));
                        if (input != null) {
                            String command = input.optString("command").trim();
                            String step = !command.isEmpty() ? command
                                    : kindOf(name, input) == ACT_HELPER ? helperStep(input)
                                    : "websearch".equals(name) ? "web search: " + input.optString("query")
                                    : "webfetch".equals(name) ? "web page: " + input.optString("url") : "";
                            if (!step.isEmpty() && ran.size() < 200) ran.add(step);
                        }
                        return false;
                    }
                    case "session.tool.success":
                    case "session.tool.failed":
                        doing(ACT_RESULT, "");
                        return false;
                    case "session.retry.scheduled":
                        doing(ACT_RETRY, "");
                        return false;
                    case "session.text.started":
                    case "session.text.delta":
                        break;
                    default:
                        return false;
                }
                doing(ACT_WRITE, "");
                String id = d.optString("assistantMessageID") + ":" + d.optInt("ordinal");
                if (!id.equals(part)) {
                    part = id;
                    text.setLength(0);
                }
                String delta = d.optString("delta");
                if (delta.isEmpty()) return true;
                text.append(delta);
                pieces++;
                if (firstAfter < 0) firstAfter = android.os.SystemClock.elapsedRealtime() - began;
                return true;
            } catch (Exception e) {
                return false;
            }
        }

        private void show(final String soFar) {
            main.post(new Runnable() {
                @Override
                public void run() {
                    if (id != Chat.this.run || done) return;
                    live = soFar.trim().isEmpty() ? "" : soFar.replaceFirst("^\\s+", "");
                    if (live.isEmpty()) return; // keep the paragraph before, until the next one has words
                    reply.text = live;
                    changed();
                }
            });
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
            } else if (line.startsWith("! retrying (attempt ") || WAITS_FOR_HELPERS.matcher(line).matches()) {
                // OpenCode's own progress line, not something the model said
                inCommand = false;
            } else if (!inCommand && HELPER_LINE.matcher(line).matches()) {
                // "✓ Find out about chai Researcher Agent": a part of the job went to a helper agent
                result.steps.add("Helper: " + line.substring(2, line.length() - " Agent".length()).trim());
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
        if (watcher != null) watcher.run();
    }

    // ------------------------------------------------------------ storage

    private void save() {
        try {
            JSONArray list = new JSONArray();
            String title = "";
            for (Message m : messages) {
                if (title.isEmpty() && m.user) {
                    // an attached file is named, without its path
                    String said = m.text.replaceAll("\\[file: (?:[^\\]\\n]*/)?([^\\]\\n/]+)\\]", "$1");
                    title = said.length() > 80 ? said.substring(0, 80) : said;
                }
                list.put(new JSONObject().put("user", m.user).put("text", m.text).put("first", m.first)
                        .put("failed", m.failed).put("note", m.note).put("steps", new JSONArray(m.steps))
                        .put("pictures", new JSONArray(m.pictures)));
            }
            // a chat the agent began: named after what it asked
            if (title.isEmpty() && !messages.isEmpty()) {
                String began = messages.get(0).text.replaceAll("\\s+", " ").trim();
                title = began.length() > 80 ? began.substring(0, 80) : began;
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
                m.first = j.optBoolean("first");
                JSONArray steps = j.optJSONArray("steps");
                for (int k = 0; steps != null && k < steps.length(); k++) m.steps.add(steps.getString(k));
                JSONArray pictures = j.optJSONArray("pictures");
                for (int k = 0; pictures != null && k < pictures.length(); k++) m.pictures.add(pictures.getString(k));
                // a reply that was cut off when the app was closed
                if (m.first && m.text.isEmpty()) continue;
                if (!m.user && m.text.isEmpty()) m.text = Lang.of(context).getString(R.string.chat_stopped);
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
