package com.termant.app;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import android.util.Base64;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

/**
 * A live voice call with Gemini: the user talks, the phone talks back, and while
 * they talk the model can run a command in the phone's Ubuntu terminal (the run
 * tool), so work gets done out loud. The whole call is audio; the words on the
 * screen are only the transcriptions.
 *
 * The connection is a WebSocket to the Gemini Live API. This app carries no
 * libraries, so the frames are made and read by hand: they are simple, and there
 * are few of them. Input is 16-bit PCM at 16 kHz, output at 24 kHz.
 */
final class LiveCall {

    interface Listener {
        /** "connecting", "live", "speaking", "listening", "working". */
        void onState(String state);

        void onUserLine(String text);

        void onTermantLine(String text);

        void onWorking(String command);

        /** "ended", "quota", "no-key", "error: ...". */
        void onDone(String reason);
    }

    static final String HOST = "generativelanguage.googleapis.com";
    // The doors the call is served from, newest first
    static final String[] PATHS = {
            "/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent",
            "/v1beta/live/bidiGenerateContent",
            "/v1/live/bidiGenerateContent",
    };
    // The voices a key may open differ; try them in order, the richest first
    static final String[] MODELS = {
            "models/gemini-2.5-flash-native-audio-preview-12-2025",
            "models/gemini-2.5-flash-preview-native-audio-dialog",
            "models/gemini-2.0-flash-live-001",
    };

    private static final int IN_RATE = 16000;
    private static final int OUT_RATE = 24000;

    private final Context context;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final SecureRandom random = new SecureRandom();

    private volatile boolean running;
    private volatile boolean finished;
    private Socket socket;
    private DataOutputStream wsOut;
    private BufferedInputStream wsIn;
    private AudioRecord recorder;
    private AudioTrack track;
    private Thread micThread;
    private Thread readThread;
    private String geminiKey;
    private volatile boolean setupDone;
    private volatile String setupResult;
    private final Object setupGate = new Object();

    LiveCall(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    /** What the model is, and what it may do, while the call runs. */
    private static final String INSTRUCTION =
            "You are Termant, the personal assistant living on this Android phone. "
                    + "The user is talking to you out loud and you answer out loud, in the user's "
                    + "language (often Hinglish, Hindi and English mixed). Keep answers short: one "
                    + "to three sentences, unless the user asks for more. You can do real work while "
                    + "you talk: call the run tool with a single command line and it runs in the "
                    + "phone's Ubuntu terminal, the same terminal the chat uses, with tools like "
                    + "safe, readfile, study, diary, me, phone look, smol, and ordinary commands. "
                    + "When the result comes back, speak it in one or two sentences; if it failed, "
                    + "say why briefly. Never do anything destructive: no deleting the user's "
                    + "files, no sending money, no accounts. ";

    void start() {
        running = true;
        Thread t = new Thread(new Runnable() {
            @Override
            public void run() {
                loop();
            }
        }, "termant-live");
        t.start();
    }

    void stop() {
        running = false;
        closeSocket();
    }

    // ------------------------------------------------------------ the whole call

    private void loop() {
        liveLog("==== call started, " + (System.currentTimeMillis() / 1000) + " ====");
        Map<String, String> keys = Config.keys(context);
        geminiKey = keys.get("GOOGLE_GENERATIVE_AI_API_KEY");
        if (geminiKey == null || geminiKey.trim().isEmpty()) {
            finish("no-key");
            return;
        }
        say("connecting");
        IOException last = null;
        boolean up = false;
        boolean audioOn = false;
        for (String path : PATHS) {
            if (up) break;
            for (int m = 0; m < MODELS.length && !up; m++) {
                liveLog("door: " + path);
                try {
                    open(path);
                    readThread = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            readLoop();
                        }
                    }, "termant-live-read");
                    readThread.start();
                    if (!audioOn) {
                        startAudio();
                        audioOn = true;
                    }
                    liveLog("setup: " + MODELS[m]);
                    sendSetup(MODELS[m]);
                    if (waitForSetup()) {
                        up = true;
                        break;
                    }
                    if ("quota".equals(setupResult)) {
                        finish("quota");
                        return;
                    }
                    closeSocket();
                    last = new IOException(setupResult == null ? "Gemini did not answer" : setupResult);
                    if (setupResult != null && !modelMissing(setupResult)) break; // a real error, not a wrong door
                } catch (LiveReject e) {
                    if (e.quota) {
                        finish("quota");
                        return;
                    }
                    last = new IOException("code " + e.code + ": " + e.body);
                    break; // this door is closed; the next one
                } catch (IOException e) {
                    closeSocket();
                    last = e;
                    break;
                }
            }
        }
        if (!up) {
            finish("error: " + oneLine(last == null ? "could not reach Gemini" : String.valueOf(last.getMessage())));
            return;
        }
        micThread = new Thread(new Runnable() {
            @Override
            public void run() {
                micLoop();
            }
        }, "termant-live-mic");
        micThread.start();
        while (running) {
            if (micThread != null && !micThread.isAlive()) break;
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                break;
            }
        }
        cleanup();
    }

    // ------------------------------------------------------------ the WebSocket

    /** The server answered, but it did not want the call (a status other than 101). */
    private static final class LiveReject extends RuntimeException {
        final boolean quota;
        final int code;
        final String body;

        LiveReject(boolean quota, int code, String body) {
            super("code " + code);
            this.quota = quota;
            this.code = code;
            this.body = body;
        }
    }

    private void open(String path) throws IOException {
        SSLSocketFactory factory = (SSLSocketFactory) SSLSocketFactory.getDefault();
        SSLSocket s = (SSLSocket) factory.createSocket();
        s.connect(new InetSocketAddress(HOST, 443), 15000);
        s.setSoTimeout(0);
        DataOutputStream out = new DataOutputStream(s.getOutputStream());
        byte[] nonce = new byte[16];
        random.nextBytes(nonce);
        boolean headerKey = path.startsWith("/ws/");
        out.writeBytes("GET " + path + (headerKey ? "" : "?key=" + geminiKey) + " HTTP/1.1\r\n");
        out.writeBytes("Host: " + HOST + "\r\n");
        if (headerKey) out.writeBytes("x-goog-api-key: " + geminiKey + "\r\n");
        out.writeBytes("Upgrade: websocket\r\n");
        out.writeBytes("Connection: Upgrade\r\n");
        out.writeBytes("Sec-WebSocket-Key: " + Base64.encodeToString(nonce, Base64.NO_WRAP) + "\r\n");
        out.writeBytes("Sec-WebSocket-Version: 13\r\n");
        out.writeBytes("\r\n");
        out.flush();
        BufferedInputStream in = new BufferedInputStream(s.getInputStream(), 1 << 16);
        // The status line and the headers, one byte at a time, so that no byte
        // of what follows the empty line is ever swallowed by a reader ahead.
        StringBuilder statusLine = new StringBuilder();
        int c;
        while ((c = in.read()) >= 0) {
            if (c == '\n') break;
            statusLine.append((char) c);
        }
        if (c < 0) {
            closeQuietly(s);
            throw new IOException("the server closed the connection");
        }
        int p1 = -1, p2 = -1;
        while (true) {
            c = in.read();
            if (c < 0) {
                closeQuietly(s);
                throw new IOException("the server closed the connection");
            }
            if (p1 == '\r' && p2 == '\n' && c == '\n') break; // \r\n\r\n
            p2 = p1;
            p1 = c;
        }
        String[] parts = statusLine.toString().split(" ");
        boolean upgrade = parts.length >= 2 && parts[1].equals("101");
        int code = parts.length >= 2 ? parseCode(parts[1]) : 0;
        if (!upgrade) {
            StringBuilder body = new StringBuilder();
            int n = 0;
            while (n < 4000 && (c = in.read()) >= 0) {
                body.append((char) c);
                n++;
            }
            closeQuietly(s);
            throw new LiveReject(classify(body.toString(), code), code, body.toString());
        }
        socket = s;
        wsOut = out;
        wsIn = in;
    }

    private static int parseCode(String s) {
        try {
            return Integer.parseInt(s);
        } catch (Exception e) {
            return 0;
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (Exception e) {
        }
    }

    private void closeSocket() {
        Socket s = socket;
        if (s != null) closeQuietly(s);
    }

    /** The first message of the call: who the model is, the voice, the run tool. */
    private void sendSetup(String model) throws IOException {
        JSONObject setup = new JSONObject();
        try {
            setup.put("model", model);
            JSONObject gen = new JSONObject();
            gen.put("responseModalities", new JSONArray().put("AUDIO"));
            JSONObject voice = new JSONObject();
            voice.put("prebuiltVoiceConfig", new JSONObject().put("voiceName", "Puck"));
            gen.put("speechConfig", new JSONObject().put("voiceConfig", voice));
            setup.put("generationConfig", gen);
            String persona = Chat.personaText(Config.persona(context));
            setup.put("systemInstruction", new JSONObject()
                    .put("parts", new JSONArray().put(new JSONObject()
                            .put("text", INSTRUCTION + (persona.isEmpty() ? "" : " " + persona)))));
            // listen with the server's own ear, and transcribe both sides for the screen
            setup.put("realtimeInputConfig", new JSONObject()
                    .put("automaticActivityDetection", new JSONObject())
                    .put("transcription", new JSONObject()));
            setup.put("inputAudioTranscription", new JSONObject());
            setup.put("outputAudioTranscription", new JSONObject());
            JSONObject decl = new JSONObject();
            decl.put("name", "run");
            decl.put("description", "Run one command line in the phone's Ubuntu terminal, the same "
                    + "terminal the chat uses. Use it to do the work the user asks for: files, looking "
                    + "things up, and the kit tools (safe, readfile, study, diary, me, phone look, "
                    + "smol). Returns the output of the command.");
            JSONObject props = new JSONObject();
            props.put("command", new JSONObject().put("type", "STRING")
                    .put("description", "The one command line to run"));
            JSONObject params = new JSONObject();
            params.put("type", "OBJECT");
            params.put("properties", props);
            params.put("required", new JSONArray().put("command"));
            decl.put("parameters", params);
            setup.put("tools", new JSONArray().put(new JSONObject()
                    .put("functionDeclarations", new JSONArray().put(decl))));
        } catch (Exception e) {
            throw new IOException("could not build the call: " + e.getMessage());
        }
        try {
            sendText(new JSONObject().put("setup", setup).toString());
        } catch (org.json.JSONException e) {
            throw new IOException("could not build the call: " + e.getMessage());
        }
    }

    private boolean firstServerLogged;

    /** Every step of the connection, written where the phone's terminal can read it. */
    private void liveLog(String line) {
        try {
            String safe = line.replace("'", "").replace('\n', ' ').replace('\r', ' ');
            ProcessBuilder pb = new ProcessBuilder(Ubuntu.command(context,
                    "/bin/bash", "-c", "printf '%s\n' '" + safe
                            + "' >> " + Config.workspace(context) + "/.termant-live.log"));
            Map<String, String> env = pb.environment();
            env.clear();
            for (String e : Ubuntu.hostEnvironment(context)) {
                int eq = e.indexOf('=');
                if (eq > 0) env.put(e.substring(0, eq), e.substring(eq + 1));
            }
            pb.redirectErrorStream(true);
            final Process p = pb.start();
            p.getOutputStream().close();
            Thread w = new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        p.waitFor();
                    } catch (Exception e2) {
                    }
                }
            });
            w.setDaemon(true);
            w.start();
        } catch (Exception e) {
            // there is no terminal yet; the log waits for one
        }
    }

    /** A text frame, masked, as the RFC asks of every client frame. */
    private synchronized void sendText(String text) throws IOException {
        sendFrame(0x1, text.getBytes(StandardCharsets.UTF_8));
    }

    private synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
        DataOutputStream out = wsOut;
        if (out == null) throw new IOException("not connected");
        ByteArrayOutputStream head = new ByteArrayOutputStream();
        head.write(0x80 | opcode);
        long n = payload.length;
        if (n < 126) {
            head.write((int) (0x80 | n));
        } else if (n <= 0xffff) {
            head.write(0xde);
            head.write((int) (n >>> 8));
            head.write((int) (n & 0xff));
        } else {
            head.write(0xdf);
            for (int i = 7; i >= 0; i--) head.write((int) ((n >>> (8 * i)) & 0xff));
        }
        byte[] mask = new byte[4];
        random.nextBytes(mask);
        head.write(mask);
        out.write(head.toByteArray());
        for (int i = 0; i < payload.length; i++) payload[i] = (byte) (payload[i] ^ mask[i & 3]);
        out.write(payload);
        out.flush();
    }

    /** Blocks until the server accepts the call (setupComplete) or rejects it (setupError). */
    private boolean waitForSetup() {
        long deadline = System.currentTimeMillis() + 25000;
        synchronized (setupGate) {
            while (setupResult == null && running) {
                long left = deadline - System.currentTimeMillis();
                if (left <= 0) return false;
                try {
                    setupGate.wait(left);
                } catch (InterruptedException e) {
                    return false;
                }
            }
        }
        return "ok".equals(setupResult);
    }

    /** A door this key cannot open: the next model may be the right one. */
    private static boolean modelMissing(String reason) {
        String r = (reason == null ? "" : reason).toLowerCase(Locale.ROOT);
        return r.contains("not found") || r.contains("not_found")
                || r.contains("permission denied") || r.contains("permission_denied")
                || r.contains("403") || r.contains("404");
    }

    private void readLoop() {
        ByteArrayOutputStream pending = new ByteArrayOutputStream();
        int pendingOpcode = -1;
        try {
            while (running) {
                int b0 = wsIn.read();
                if (b0 < 0) break;
                int b1 = wsIn.read();
                int opcode = b0 & 0x0f;
                long len = b1 & 0x7f;
                if (len == 126) {
                    len = (wsIn.read() << 8) | wsIn.read();
                } else if (len == 127) {
                    len = 0;
                    for (int i = 0; i < 8; i++) len = (len << 8) | wsIn.read();
                }
                byte[] payload = new byte[(int) len];
                int got = 0;
                while (got < payload.length) {
                    int r = wsIn.read(payload, got, payload.length - got);
                    if (r < 0) break;
                    got += r;
                }
                if ((b0 & 0x80) == 0 || opcode == 0x0) {
                    // a piece of a longer message: keep it until the last piece
                    if (opcode != 0x0) pendingOpcode = opcode;
                    pending.write(payload);
                    if ((b0 & 0x80) == 0) continue;
                    byte[] whole = pending.toByteArray();
                    pending.reset();
                    dispatch(pendingOpcode, whole);
                    pendingOpcode = -1;
                    continue;
                }
                dispatch(opcode, payload);
            }
            if (running && setupDone) finish("ended");
        } catch (IOException e) {
            if (running && setupDone) finish("ended");
        } catch (Exception e) {
            finish("error: " + oneLine(String.valueOf(e.getMessage())));
        }
    }

    private void dispatch(int opcode, byte[] payload) throws IOException {
        if (opcode == 0x9) {
            sendFrame(0xA, payload); // ping: answer with the same bytes
            return;
        }
        if (opcode == 0x8) {
            int code = payload.length >= 2 ? (payload[0] << 8) | payload[1] : 1000;
            String body = payload.length > 2
                    ? new String(payload, 2, payload.length - 2, StandardCharsets.UTF_8) : "";
            closeSocket();
            String reason = classify(body, code) ? "quota"
                    : "error: the call was closed (" + oneLine(body) + ", code " + code + ")";
            if (setupDone) finish(reason);
            else setupFailed(reason);
            return;
        }
        if (opcode != 0x1) return;
        handleText(new String(payload, StandardCharsets.UTF_8));
    }

    // ------------------------------------------------------------ the call's words

    private void handleText(String text) {
        JSONObject o;
        try {
            o = new JSONObject(text);
        } catch (Exception e) {
            return;
        }
        if (!firstServerLogged) {
            firstServerLogged = true;
            liveLog("first answer: " + (text.length() > 300 ? text.substring(0, 300) : text));
        }
        if (o.has("setupComplete")) {
            setupDone = true;
            Config.liveBlocked(context, 0); // it answered: the quota is not out
            say("live");
            synchronized (setupGate) {
                if (setupResult == null) {
                    setupResult = "ok";
                    setupGate.notifyAll();
                }
            }
            return;
        }
        if (o.has("goAway")) {
            closeSocket();
            if (setupDone) finish("ended");
            else setupFailed("error: the call was not wanted");
            return;
        }
        if (o.has("setupError") || o.has("error")) {
            closeSocket();
            String msg = "";
            try {
                JSONObject e = o.has("setupError") ? o.getJSONObject("setupError").getJSONObject("error")
                        : o.getJSONObject("error");
                msg = e.getString("message");
            } catch (Exception e2) {
            }
            String reason = classify(msg, 0) ? "quota" : "error: " + oneLine(msg);
            if (setupDone) finish(reason);
            else setupFailed(reason);
            return;
        }
        if (o.has("toolCall")) {
            try {
                JSONArray calls = o.getJSONObject("toolCall").getJSONArray("functionCalls");
                for (int i = 0; i < calls.length(); i++) {
                    JSONObject call = calls.getJSONObject(i);
                    if (!call.optString("name").equals("run")) continue;
                    JSONObject args = call.optJSONObject("args");
                    String command = args == null ? "" : args.optString("command");
                    if (command.isEmpty()) continue;
                    final String id = call.optString("id");
                    final String theCommand = command;
                    say("working");
                    onWorking(theCommand);
                    Thread t = new Thread(new Runnable() {
                        @Override
                        public void run() {
                            runCommand(theCommand, id);
                        }
                    }, "termant-live-tool");
                    t.start();
                }
            } catch (Exception e) {
            }
            return;
        }
        if (o.has("serverContent")) {
            try {
                JSONObject sc = o.getJSONObject("serverContent");
                if (sc.has("inputTranscription"))
                    onUserLine(sc.getJSONObject("inputTranscription").optString("text"));
                if (sc.has("outputTranscription"))
                    onTermantLine(sc.getJSONObject("outputTranscription").optString("text"));
                if (sc.has("modelTurn")) {
                    say("speaking");
                    JSONArray parts = sc.getJSONObject("modelTurn").optJSONArray("parts");
                    AudioTrack t = track;
                    if (parts != null && t != null) {
                        for (int i = 0; i < parts.length(); i++) {
                            JSONObject p = parts.getJSONObject(i);
                            if (!p.has("inlineData")) continue;
                            JSONObject data = p.getJSONObject("inlineData");
                            if (!data.optString("mimeType").startsWith("audio/pcm")) continue;
                            byte[] audio = Base64.decode(data.getString("data"), Base64.NO_WRAP);
                            int w = 0;
                            while (w < audio.length) w += t.write(audio, w, audio.length - w);
                        }
                    }
                }
                if (sc.optBoolean("interrupted")) say("listening");
            } catch (Exception e) {
                // one bad message must not kill the call
            }
            return;
        }
        // usageMetadata and the rest: nothing to do
    }

    /** The setup was refused: tell the door-trying loop, do not end the call. */
    private void setupFailed(String reason) {
        synchronized (setupGate) {
            if (setupResult == null) {
                setupResult = reason;
                setupGate.notifyAll();
            }
        }
    }

    // ------------------------------------------------------------ the work, out loud

    private void runCommand(String command, String id) {
        String result;
        try {
            result = runTool(command);
        } catch (Exception e) {
            result = "(the command could not run: " + oneLine(String.valueOf(e.getMessage())) + ")";
        }
        try {
            JSONObject resp = new JSONObject().put("name", "run")
                    .put("response", new JSONObject().put("result", result));
            if (id != null && !id.isEmpty()) resp.put("id", id);
            String msg = new JSONObject()
                    .put("toolResponse", new JSONObject()
                            .put("functionResponses", new JSONArray().put(resp)))
                    .toString();
            sendText(msg);
            say("speaking");
        } catch (Exception e) {
            finish("error: the call closed while the command ran");
        }
    }

    /** One command in the phone's Ubuntu terminal, 90 seconds at most, 8 KB shown. */
    private String runTool(String command) throws Exception {
        String line = "source " + Config.KEYS_FILE + " 2>/dev/null; cd "
                + Config.workspace(context) + " 2>/dev/null; " + command;
        ProcessBuilder pb = new ProcessBuilder(Ubuntu.command(context, "/bin/bash", "-c", line));
        pb.redirectErrorStream(true);
        Map<String, String> env = pb.environment();
        env.clear();
        for (String e : Ubuntu.hostEnvironment(context)) {
            int eq = e.indexOf('=');
            if (eq > 0) env.put(e.substring(0, eq), e.substring(eq + 1));
        }
        env.put("TERMANT_NO_INSTALL", "1"); // a voice call does not make big installs
        Process p = pb.start();
        p.getOutputStream().close();
        byte[] buf = new byte[4096];
        DataInputStream in = new DataInputStream(p.getInputStream());
        final ByteArrayOutputStream out = new ByteArrayOutputStream();
        Thread drain = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    int n;
                    while ((n = in.read(buf)) > 0)
                        synchronized (out) {
                            if (out.size() < 8000) out.write(buf, 0, Math.min(n, 8000 - out.size()));
                        }
                } catch (IOException ignored) {
                    // the stream closed when the process ended or was destroyed
                }
            }
        });
        drain.setDaemon(true);
        drain.start();
        Thread waitEnd = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    p.waitFor();
                } catch (InterruptedException ignored) {
                    // the call is ending; the wait below decides
                }
            }
        });
        waitEnd.setDaemon(true);
        waitEnd.start();
        waitEnd.join(90 * 1000L);
        if (waitEnd.isAlive()) {
            p.destroy();
            waitEnd.join(5000L);
            p.destroyForcibly();
            return "(timed out after 90 seconds)";
        }
        drain.join(2000L);
        String text;
        synchronized (out) {
            text = out.toString("UTF-8").trim();
        }
        int code = p.exitValue();
        if (text.isEmpty()) return code == 0 ? "(done, nothing to show)" : "(exit " + code + ", nothing printed)";
        return code == 0 ? text : "(exit " + code + ") " + text;
    }

    // ------------------------------------------------------------ the voice

    private void startAudio() throws IOException {
        int minIn = AudioRecord.getMinBufferSize(IN_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        if (minIn < 0) throw new IOException("this phone cannot record audio");
        try {
            recorder = new AudioRecord(MediaRecorder.AudioSource.VOICE_COMMUNICATION, IN_RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(minIn, 3200));
        } catch (Exception e) {
            recorder = null;
            throw new IOException("the microphone could not start");
        }
        if (recorder.getState() != AudioRecord.STATE_INITIALIZED) {
            recorder.release();
            recorder = null;
            throw new IOException("the microphone could not start");
        }
        try {
            AudioAttributes attrs = new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build();
            AudioFormat fmt = new AudioFormat.Builder()
                    .setSampleRate(OUT_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build();
            track = new AudioTrack.Builder()
                    .setAudioAttributes(attrs)
                    .setAudioFormat(fmt)
                    .setTransferMode(AudioTrack.MODE_STREAM)
                    .setBufferSizeInBytes(OUT_RATE * 2 * 4)
                    .build();
            if (track.getState() != AudioTrack.STATE_INITIALIZED) {
                track.release();
                track = null;
                throw new IOException("the speaker could not start");
            }
            track.play();
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            if (track != null) track.release();
            track = null;
            throw new IOException("the speaker could not start");
        }
        recorder.startRecording();
    }

    private void micLoop() {
        byte[] chunk = new byte[1280]; // 40 ms of 16 kHz mono 16-bit
        try {
            while (running) {
                int n = recorder.read(chunk, 0, chunk.length);
                if (n <= 0) continue;
                byte[] part = n == chunk.length ? chunk : Arrays.copyOf(chunk, n);
                JSONObject piece = new JSONObject()
                        .put("mimeType", "audio/pcm;rate=16000")
                        .put("data", Base64.encodeToString(part, Base64.NO_WRAP));
                String msg = new JSONObject()
                        .put("realtimeInput", new JSONObject()
                                .put("mediaChunks", new JSONArray().put(piece)))
                        .toString();
                sendText(msg);
            }
        } catch (IOException e) {
            // the socket went away; the read thread ends the call
        } catch (Exception e) {
            finish("error: the microphone stopped (" + oneLine(String.valueOf(e.getMessage())) + ")");
        }
    }

    // ------------------------------------------------------------ the ending

    private void cleanup() {
        running = false;
        AudioRecord r = recorder;
        if (r != null) {
            try {
                r.stop();
            } catch (Exception e) {
            }
            r.release();
            recorder = null;
        }
        AudioTrack t = track;
        if (t != null) {
            try {
                t.stop();
            } catch (Exception e) {
            }
            t.release();
            track = null;
        }
        closeSocket();
        if (!finished) finish("ended");
    }

    /** First reason wins; it is told to the screen and the quota is remembered. */
    private void finish(String reason) {
        if (finished) return;
        finished = true;
        running = false;
        liveLog("done: " + reason);
        if (reason.equals("quota"))
            Config.liveBlocked(context, System.currentTimeMillis() + 24L * 3600 * 1000);
        closeSocket();
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onDone(reason);
            }
        });
    }

    // ------------------------------------------------------------ to the screen

    private void say(final String state) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onState(state);
            }
        });
    }

    private void onWorking(final String command) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onWorking(command);
            }
        });
    }

    private void onUserLine(final String text) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onUserLine(text);
            }
        });
    }

    private void onTermantLine(final String text) {
        main.post(new Runnable() {
            @Override
            public void run() {
                listener.onTermantLine(text);
            }
        });
    }

    /** "quota" means the call must not be offered again for a while. */
    static boolean classify(String message, int code) {
        if (code == 429) return true;
        String m = (message == null ? "" : message).toLowerCase(Locale.ROOT);
        return m.contains("429") || m.contains("quota")
                || m.contains("resource has been exhausted") || m.contains("resource_exhausted");
    }

    private static String oneLine(String s) {
        String t = (s == null ? "" : s).replace('\n', ' ').replace('\r', ' ').trim();
        while (t.contains("  ")) t = t.replace("  ", " ");
        return t.length() > 120 ? t.substring(0, 120) + "..." : t;
    }
}
