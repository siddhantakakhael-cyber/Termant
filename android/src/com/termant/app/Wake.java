package com.termant.app;

import android.content.Context;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.speech.RecognitionListener;
import android.speech.SpeechRecognizer;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Waits for the words "hey Termant" so the user can give the agent a task by
 * voice without touching the phone. It runs as long as the service does: when
 * the wake word is heard it waits for the task, sends it to the chat, and goes
 * back to listening. The words are heard by the phone's own speech service,
 * the same one the microphone button uses; whether that service keeps working
 * with the screen locked is up to the phone.
 */
final class Wake implements RecognitionListener {

    /** What the service wants of the listener; all callbacks are on the main thread. */
    interface Sink {
        /** The task heard after the wake word. */
        void onCommand(String text);

        /** The wake word was just heard: the next words are the task. */
        void onArmed(boolean armed);

        /** Listening cannot go on: "detail" says why, and the setting is turned off. */
        void onProblem(String detail);
    }

    /** "termant" and the words a microphone writes for it. */
    private static final Pattern WAKE = Pattern.compile("t[ea]a?r?m[ae]?nt");
    private static final int ARMED_FOR_MS = 15 * 1000;
    private static final int MOST_ERRORS = 5;

    private final Context context;
    private final Sink sink;
    private final Handler main = new Handler(Looper.getMainLooper());
    private SpeechRecognizer recognizer;
    private boolean wanted;
    private boolean suspended;
    private boolean armed;
    private boolean ignoring;
    private int errors;

    Wake(Context context, Sink sink) {
        this.context = context;
        this.sink = sink;
    }

    boolean isOn() {
        return wanted;
    }

    void start() {
        if (wanted) return;
        wanted = true;
        suspended = false; // a pause while dictating does not outlive a new start
        errors = 0;
        listening();
    }

    void stop() {
        wanted = false;
        main.removeCallbacks(disarm);
        main.removeCallbacks(listeningNow);
        armed = false;
        cancelRec();
        sink.onArmed(false);
    }

    /** While the app's own microphone button listens, this one must be quiet. */
    void suspend(boolean on) {
        if (suspended == on) return;
        suspended = on;
        if (on) {
            armed = false;
            main.removeCallbacks(disarm);
            main.removeCallbacks(listeningNow);
            sink.onArmed(false);
            cancelRec();
        } else if (wanted) {
            errors = 0;
            listening();
        }
    }

    void destroy() {
        stop();
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Exception e) {
                // already gone
            }
            recognizer = null;
        }
    }

    private final Runnable listeningNow = new Runnable() {
        @Override
        public void run() {
            listening();
        }
    };

    /** Asks the speech service to listen once more; it ends after every answer. */
    private void listening() {
        if (!wanted || suspended) return;
        if (!Voice.available(context)) {
            giveUp("no speech service");
            return;
        }
        if (recognizer == null) {
            try {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context);
                recognizer.setRecognitionListener(this);
            } catch (Exception e) {
                giveUp(String.valueOf(e.getMessage()));
                return;
            }
        }
        errors = 0;
        ignoring = false;
        try {
            recognizer.startListening(Voice.request(context));
        } catch (Exception e) {
            fail(e);
        }
    }

    private void fail(Exception e) {
        android.util.Log.i("Termant", "wake: " + e);
        errors++;
        if (errors >= MOST_ERRORS) giveUp(String.valueOf(e.getMessage()));
        else if (wanted && !suspended) main.postDelayed(listeningNow, 2000);
    }

    private void giveUp(String detail) {
        wanted = false;
        armed = false;
        main.removeCallbacks(disarm);
        main.removeCallbacks(listeningNow);
        cancelRec();
        sink.onArmed(false);
        sink.onProblem(detail);
    }

    private void cancelRec() {
        if (recognizer == null) return;
        ignoring = true;
        try {
            recognizer.cancel();
        } catch (Exception e) {
            // already gone
        }
    }

    private void arm() {
        if (armed) return;
        armed = true;
        main.removeCallbacks(disarm);
        main.postDelayed(disarm, ARMED_FOR_MS);
        sink.onArmed(true);
    }

    private final Runnable disarm = new Runnable() {
        @Override
        public void run() {
            if (armed) {
                armed = false;
                sink.onArmed(false);
            }
        }
    };

    /**
     * One result of the speech service: while it waits for the wake word, the
     * words after it (if any) are the task; after the wake word, the next
     * words are the task.
     */
    private void heard(String text, boolean last) {
        if (!wanted || suspended) return;
        String n = norm(text);
        if (!last) {
            if (!armed && WAKE.matcher(n).find()) arm();
            return;
        }
        Matcher m = WAKE.matcher(n);
        boolean found = m.find();
        if (found && armed) {
            if (norm(tailAfter(text, m.end())).length() < 3) return; // the wake word again
            command(tailAfter(text, m.end()));
        } else if (armed) {
            command(text);
        } else if (found) {
            String tail = tailAfter(text, m.end());
            if (norm(tail).length() >= 3) command(tail);
            else arm();
        }
    }

    private void command(String raw) {
        String text = raw.replaceFirst("^[^A-Za-z0-9]+", "").trim();
        if (norm(text).length() < 2) return;
        armed = false;
        main.removeCallbacks(disarm);
        sink.onArmed(false);
        sink.onCommand(text);
    }

    private static String norm(String s) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = Character.toLowerCase(s.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) out.append(c);
        }
        return out.toString();
    }

    /** What the raw words say from the given place in the counted letters on. */
    private static String tailAfter(String raw, int counted) {
        int seen = 0;
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            boolean countedChar = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9');
            if (countedChar) {
                if (seen == counted) return raw.substring(i);
                seen++;
            }
        }
        return "";
    }

    // ------------------------------------------------------------ RecognitionListener

    @Override
    public void onPartialResults(Bundle partial) {
        if (ignoring) return;
        String text = Voice.words(partial);
        if (!text.isEmpty()) heard(text, false);
    }

    @Override
    public void onResults(Bundle results) {
        if (ignoring) return;
        String text = Voice.words(results);
        if (!text.isEmpty()) heard(text, true);
    }

    @Override
    public void onError(int error) {
        android.util.Log.i("Termant", "wake: error " + error);
        if (ignoring || !wanted || suspended) return;
        if (error == SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS) {
            giveUp("microphone not allowed");
            return;
        }
        errors++;
        if (errors >= MOST_ERRORS) giveUp("the speech service stopped (error " + error + ")");
        else main.postDelayed(listeningNow, 2000);
    }

    @Override
    public void onEndOfSpeech() {
        if (ignoring) return;
        if (wanted && !suspended) main.postDelayed(listeningNow, 300);
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
    }

    @Override
    public void onBeginningOfSpeech() {
    }

    @Override
    public void onRmsChanged(float rmsdB) {
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
    }
}
