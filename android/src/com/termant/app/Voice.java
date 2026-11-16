package com.termant.app;

import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import java.util.ArrayList;

/**
 * Dictation inside the app: listens through the phone's speech service and
 * hands over the words while they are spoken, without the service's own
 * pop-up window. Must be used from the main thread.
 */
final class Voice implements RecognitionListener {
    interface Listener {
        void onListening(boolean listening);

        /** The words heard so far; last is true once the sentence is complete. */
        void onHeard(String text, boolean last);

        /**
         * Listening ended without words. message is a string resource, or 0
         * when the phone's speech service cannot be used this way and its own
         * window should be tried instead.
         */
        void onProblem(int message, String detail);
    }

    private final Context context;
    private final Listener listener;
    private SpeechRecognizer recognizer;
    private boolean listening, cancelled, stopping;

    Voice(Context context, Listener listener) {
        this.context = context;
        this.listener = listener;
    }

    /** Whether this phone has a speech service at all. */
    static boolean available(Context c) {
        try {
            return SpeechRecognizer.isRecognitionAvailable(c);
        } catch (Exception e) {
            return false;
        }
    }

    /** What to ask the speech service for; also used for its pop-up window. */
    static Intent request(Context c) {
        String language = Lang.speechTag(c);
        return new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE, language)
                .putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, language)
                .putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, c.getPackageName())
                .putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
                .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1);
    }

    boolean isListening() {
        return listening;
    }

    void start() {
        if (listening) return;
        try {
            if (recognizer == null) {
                recognizer = SpeechRecognizer.createSpeechRecognizer(context);
                recognizer.setRecognitionListener(this);
            }
            cancelled = false;
            stopping = false;
            recognizer.startListening(request(context));
            setListening(true);
        } catch (Exception e) {
            android.util.Log.e("Termant", "voice: cannot start", e);
            setListening(false);
            listener.onProblem(0, String.valueOf(e.getMessage()));
        }
    }

    /** The user is done speaking: ask for the final words now. */
    void stop() {
        if (!listening || recognizer == null) return;
        if (stopping) { // asked already and the service did not answer: give up on it
            cancel();
            return;
        }
        stopping = true;
        try {
            recognizer.stopListening();
        } catch (Exception e) {
            cancel();
        }
    }

    /** Stops without a result, e.g. when the app leaves the screen. */
    void cancel() {
        if (recognizer == null) return;
        cancelled = true;
        try {
            recognizer.cancel();
        } catch (Exception e) {
            // the service is already gone
        }
        setListening(false);
    }

    void destroy() {
        cancel();
        if (recognizer != null) {
            try {
                recognizer.destroy();
            } catch (Exception e) {
                // the service is already gone
            }
        }
        recognizer = null;
    }

    private void setListening(boolean on) {
        if (listening == on) return;
        listening = on;
        listener.onListening(on);
    }

    private static String words(Bundle results) {
        if (results == null) return "";
        ArrayList<String> heard = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
        String text = heard == null || heard.isEmpty() || heard.get(0) == null ? "" : heard.get(0);
        // Google's service keeps the words it is still unsure about apart
        ArrayList<String> unsure = results.getStringArrayList("android.speech.extra.UNSTABLE_TEXT");
        if (unsure != null && !unsure.isEmpty() && unsure.get(0) != null) text = text + unsure.get(0);
        return text.trim();
    }

    @Override
    public void onPartialResults(Bundle partial) {
        if (cancelled) return;
        String text = words(partial);
        if (!text.isEmpty()) listener.onHeard(text, false);
    }

    @Override
    public void onResults(Bundle results) {
        if (cancelled) return;
        setListening(false);
        String text = words(results);
        if (text.isEmpty()) listener.onProblem(R.string.voice_nothing, "");
        else listener.onHeard(text, true);
    }

    @Override
    public void onError(int error) {
        android.util.Log.i("Termant", "voice: error " + error);
        boolean wasListening = listening;
        setListening(false);
        if (cancelled || !wasListening) return;
        switch (error) {
            case SpeechRecognizer.ERROR_NO_MATCH:
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                listener.onProblem(R.string.voice_nothing, "");
                break;
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                listener.onProblem(R.string.voice_denied, "");
                break;
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
            case SpeechRecognizer.ERROR_SERVER:
                listener.onProblem(R.string.voice_failed, "network " + error);
                break;
            default:
                // busy, no such language, or a service that only works through its own window
                listener.onProblem(0, "error " + error);
        }
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
    public void onEndOfSpeech() {
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
    }
}
