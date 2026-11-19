package com.termant.app;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A question the agent puts to the user in the middle of its work
 * (`phone ask "Question?" "Choice A" "Choice B"`, or without choices for a
 * typed answer). It is shown wherever the user is looking: in a card over
 * the app that is on screen (see Overlay), or in a dialog inside Termant.
 * The command waits here until the user answers.
 */
final class Ask {
    static final class Question {
        final String text;
        final List<String> choices;
        private final CountDownLatch done = new CountDownLatch(1);
        private volatile String answer;

        Question(String text, List<String> choices) {
            this.text = text;
            this.choices = choices;
        }
    }

    static final long WAIT_MS = 240_000;
    private static volatile Question pending;

    private Ask() {
    }

    /** The question waiting for an answer, or null. */
    static Question pending() {
        return pending;
    }

    /** Shows the question and waits for the answer. Not for the main thread. */
    static String ask(TermService service, String text, List<String> choices) throws Exception {
        String question = text.trim();
        if (question.isEmpty()) {
            throw new PhoneBridge.Refused("usage: phone ask \"Question?\" [\"Choice 1\" \"Choice 2\" ...]\n"
                    + "With choices the user picks one (or types something else); without, the user types the answer.");
        }
        if (question.length() > 600) throw new PhoneBridge.Refused("phone ask: the question is too long (600 letters at most).");
        List<String> options = new ArrayList<>();
        for (String c : choices) {
            String choice = c.trim();
            if (choice.isEmpty()) continue;
            if (choice.length() > 80) throw new PhoneBridge.Refused("phone ask: a choice is too long (80 letters at most).");
            options.add(choice);
        }
        if (options.size() > 6) throw new PhoneBridge.Refused("phone ask: 6 choices at most.");
        Question q = new Question(question, options);
        synchronized (Ask.class) {
            if (pending != null) {
                throw new PhoneBridge.Refused("Another question is still waiting for the user's answer. Ask one at a time.");
            }
            pending = q;
        }
        android.util.Log.i("Termant", "ask: " + question + (options.isEmpty() ? " (typed answer)" : " " + options));
        service.surfacesChanged();
        try {
            if (!q.done.await(WAIT_MS, TimeUnit.MILLISECONDS)) {
                throw new PhoneBridge.Refused("The user did not answer within " + WAIT_MS / 60000 + " minutes. "
                        + "Do not go on with the step you asked about: end your reply with the question, "
                        + "so the user can answer in the chat.");
            }
            if (q.answer == null) {
                throw new PhoneBridge.Refused("The user closed the question without answering. "
                        + "Do not go on with the step you asked about; say what you did not do.");
            }
            android.util.Log.i("Termant", "ask: answered");
            return q.answer;
        } finally {
            synchronized (Ask.class) {
                if (pending == q) pending = null;
            }
            service.surfacesChanged();
        }
    }

    static void answer(Question q, String answer) {
        String a = answer == null ? "" : answer.trim();
        if (a.isEmpty()) return;
        q.answer = a;
        q.done.countDown();
    }

    /** The user does not want to answer (or stopped the agent). */
    static void close(Question q) {
        if (q != null) q.done.countDown();
    }
}
