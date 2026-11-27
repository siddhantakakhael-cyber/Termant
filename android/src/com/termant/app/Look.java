package com.termant.app;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Says in plain lines what Mirage saw in a picture: the scene, the things with
 * the place of each, the writing, and what the things do with each other. It is
 * what `phone look` prints.
 *
 * Mirage gives a box around each thing, and that box is about right; its own
 * words for the place ("left foreground") mix up left and right now and then.
 * So the place is worked out here from the box, and of Mirage's words only
 * those stay that say something else ("on bus", "background").
 *
 * The same lines are made on Windows by `pc look` (windows/resources/termant-pc.ps1);
 * android/mirage/look holds answers of Mirage and the lines both must make of them.
 * This file uses nothing of Android but org.json, so that test can run on any computer.
 */
final class Look {
    /** Words about left, right, up and down: the box says that better. */
    private static final Set<String> PLAIN = new HashSet<>(Arrays.asList("left", "right", "center", "centre", "middle",
            "top", "bottom", "upper", "lower", "edge", "and", "foreground", "background", "corner", "side", "far", "of",
            "the", "in", "at", "on"));
    private static final String[][] CELLS = {{"top left", "top", "top right"}, {"left", "middle", "right"},
            {"bottom left", "bottom", "bottom right"}};
    private static final String[] ROWS = {"top", "middle", "bottom"}, COLUMNS = {"left", "middle", "right"};
    private static final int MOST_THINGS = 30, MOST_WRITING = 40, MOST_REGIONS = 12, MOST_MAYBE = 8;

    private Look() {
    }

    private static final class Thing {
        String name, position, looks;
        double score;
        double[] box;
        int at;
    }

    private static final class Line {
        final double score;
        final int at;
        final String text;

        Line(double score, int at, String text) {
            this.score = score;
            this.at = at;
            this.text = text;
        }
    }

    /**
     * @param answer what Mirage answered
     * @param width  of the picture in pixels (for the points)
     * @param what   how the picture is called in the first line
     * @param device "phone" or "PC"
     * @param tap    added to the heading of the things, to say what a point is good for; can be empty
     */
    static String text(JSONObject answer, int width, int height, String what, long ms, String device, String tap) {
        StringBuilder out = new StringBuilder();
        out.append(what).append(" (").append(width).append('x').append(height).append("), seen by Mirage in ")
                .append(ms / 1000).append('.').append(ms % 1000 / 100).append(" s.\n");
        out.append("Mirage is a small model on this ").append(device).append(": the places are about right, but a name ")
                .append("can be wrong even with a high score (say \"it looks like\"), it does not know who a person is, ")
                .append("and it reads Latin letters only.\n");

        Object reasoning = answer.opt("reasoning");
        if (reasoning instanceof String) {
            String[] parts = ((String) reasoning).split(" \\| ", -1);
            if (parts.length >= 2 && parts[0].startsWith("lang:") && !clean(parts[1], 300).isEmpty()) {
                out.append("In short: ").append(clean(parts[1], 300)).append('\n');
            }
        }

        JSONObject scene = answer.optJSONObject("scene");
        if (scene != null) {
            StringBuilder s = new StringBuilder();
            for (Iterator<String> keys = scene.keys(); keys.hasNext(); ) {
                String key = keys.next(), value = word(scene.opt(key), 80);
                if (value.isEmpty()) continue;
                if (s.length() > 0) s.append("; ");
                s.append(clean(key, 40).replace('_', ' ')).append(" = ").append(value);
            }
            if (s.length() > 0) out.append("Scene: ").append(s).append('\n');
        }

        // ---- the things: each with its box; the same box under two names is one thing "a or b"
        Map<String, String> names = new LinkedHashMap<>();
        List<Thing> things = new ArrayList<>();
        JSONArray objects = answer.optJSONArray("objects");
        for (int i = 0; objects != null && i < objects.length(); i++) {
            JSONObject o = objects.optJSONObject(i);
            if (o == null) continue;
            String name = word(o.opt("name"), 80);
            double[] box = box(o);
            if (name.isEmpty() || box == null) continue;
            remember(names, o.opt("id"), name);
            Thing same = null;
            for (Thing t : things) {
                if (Arrays.equals(t.box, box)) {
                    same = t;
                    break;
                }
            }
            if (same != null) {
                if (!Arrays.asList(same.name.split(" or ", -1)).contains(name)) same.name += " or " + name;
                continue;
            }
            Thing t = new Thing();
            t.name = name;
            t.score = number(o.opt("score"));
            t.box = box;
            t.position = position(word(o.opt("position"), 80));
            t.looks = looks(o);
            t.at = things.size();
            things.add(t);
        }
        Map<String, List<Thing>> groups = new LinkedHashMap<>();
        for (Thing t : things) {
            List<Thing> g = groups.get(t.name);
            if (g == null) groups.put(t.name, g = new ArrayList<>());
            g.add(t);
        }
        List<Line> lines = new ArrayList<>();
        for (Map.Entry<String, List<Thing>> e : groups.entrySet()) {
            String name = e.getKey();
            List<Thing> g = e.getValue();
            Collections.sort(g, new Comparator<Thing>() {
                @Override
                public int compare(Thing a, Thing b) {
                    int c = Double.compare(b.score, a.score);
                    return c != 0 ? c : Integer.compare(a.at, b.at);
                }
            });
            if (g.size() < 4) {
                for (Thing t : g) {
                    StringBuilder s = new StringBuilder("- ").append(name).append(", ").append(percent(t.score)).append("%: ")
                            .append(place(t.box)).append(", ").append(share(t.box)).append(" of the picture");
                    if (!t.position.isEmpty()) s.append("; ").append(t.position);
                    if (!t.looks.isEmpty()) s.append("; ").append(t.looks);
                    s.append(' ').append(point(t.box, width, height));
                    lines.add(new Line(t.score, t.at, s.toString()));
                }
            } else {
                // many of one kind (the windows of a house): one line, with how many are where
                final int[] count = new int[9];
                List<Integer> cells = new ArrayList<>();
                for (Thing t : g) {
                    int c = row(t.box) * 3 + column(t.box);
                    if (count[c]++ == 0) cells.add(c);
                }
                Collections.sort(cells, new Comparator<Integer>() {
                    @Override
                    public int compare(Integer a, Integer b) {
                        return count[a] != count[b] ? Integer.compare(count[b], count[a]) : Integer.compare(a, b);
                    }
                });
                StringBuilder s = new StringBuilder("- ").append(name).append(" x").append(g.size()).append(", ")
                        .append(percent(g.get(g.size() - 1).score)).append('-').append(percent(g.get(0).score)).append("%: ");
                for (int i = 0; i < cells.size(); i++) {
                    int c = cells.get(i);
                    if (i > 0) s.append(", ");
                    s.append(count[c]).append(' ').append(CELLS[c / 3][c % 3]);
                }
                lines.add(new Line(g.get(0).score, g.get(0).at, s.toString()));
            }
        }
        Collections.sort(lines, new Comparator<Line>() {
            @Override
            public int compare(Line a, Line b) {
                int c = Double.compare(b.score, a.score);
                return c != 0 ? c : Integer.compare(a.at, b.at);
            }
        });
        if (!lines.isEmpty()) {
            out.append("Things (").append(things.size()).append("), highest score first. Each line: name, score, where its ")
                    .append("middle is, how much of the picture it covers, how it looks, and its middle point").append(tap)
                    .append(":\n");
            for (int i = 0; i < lines.size() && i < MOST_THINGS; i++) out.append(lines.get(i).text).append('\n');
            if (lines.size() > MOST_THINGS) out.append('(').append(lines.size() - MOST_THINGS).append(" more left out)\n");
        }

        // ---- what Mirage names without a box
        List<String> also = new ArrayList<>();
        JSONArray regions = answer.optJSONArray("regions");
        for (int i = 0; regions != null && i < regions.length(); i++) {
            JSONObject o = regions.optJSONObject(i);
            if (o == null) continue;
            String name = word(o.opt("name"), 80);
            if (name.isEmpty()) continue;
            remember(names, o.opt("id"), name);
            if (groups.containsKey(name)) continue;
            StringBuilder extra = new StringBuilder();
            for (String x : new String[]{word(o.opt("count"), 80), word(o.opt("position"), 80), looks(o)}) {
                if (x.isEmpty()) continue;
                if (extra.length() > 0) extra.append("; ");
                extra.append(x);
            }
            also.add(extra.length() > 0 ? name + " (" + extra + ")" : name);
        }
        if (!also.isEmpty()) {
            out.append("Also there, place not marked: ");
            for (int i = 0; i < also.size() && i < MOST_REGIONS; i++) out.append(i > 0 ? ", " : "").append(also.get(i));
            out.append('\n');
        }

        // ---- the writing
        List<String> writing = new ArrayList<>();
        JSONArray texts = answer.optJSONArray("text");
        for (int i = 0; texts != null && i < texts.length(); i++) {
            JSONObject t = texts.optJSONObject(i);
            if (t == null) continue;
            String s = word(t.opt("string"), 120);
            double[] box = box(t);
            if (s.isEmpty() || box == null) continue;
            if (s.length() < 2 && number(t.opt("score")) < 0.5) continue; // mostly an icon taken for a letter
            writing.add("- \"" + s + "\": " + CELLS[row(box)][column(box)] + " " + point(box, width, height));
        }
        if (!writing.isEmpty()) {
            out.append("Writing (letters can be wrong):\n");
            for (int i = 0; i < writing.size() && i < MOST_WRITING; i++) out.append(writing.get(i)).append('\n');
        }

        // ---- who does what with what
        List<String> maybe = new ArrayList<>();
        JSONArray events = answer.optJSONArray("events");
        for (int i = 0; events != null && i < events.length(); i++) {
            JSONObject e = events.optJSONObject(i);
            if (e != null) sentence(maybe, names, e.opt("actor"), e.opt("action"), e.opt("target"));
        }
        JSONArray relations = answer.optJSONArray("relations");
        for (int i = 0; relations != null && i < relations.length(); i++) {
            JSONObject e = relations.optJSONObject(i);
            if (e != null && !nothing(e.opt("object"))) {
                sentence(maybe, names, e.opt("subject"), e.opt("predicate"), e.opt("object"));
            }
        }
        if (!maybe.isEmpty()) {
            out.append("Maybe (this part is often wrong): ");
            for (int i = 0; i < maybe.size() && i < MOST_MAYBE; i++) out.append(i > 0 ? "; " : "").append(maybe.get(i));
            out.append('\n');
        }

        if (lines.isEmpty() && also.isEmpty() && writing.isEmpty()) out.append("Nothing was recognised in it.\n");
        return out.toString();
    }

    private static boolean nothing(Object v) {
        return v == null || v == JSONObject.NULL;
    }

    private static void remember(Map<String, String> names, Object id, String name) {
        if (nothing(id)) return;
        String key = String.valueOf(id);
        if (!names.containsKey(key)) names.put(key, name);
    }

    private static void sentence(List<String> maybe, Map<String, String> names, Object who, Object does, Object whom) {
        if (nothing(who)) return;
        String a = names.get(String.valueOf(who)), verb = word(does, 80).replace('_', ' ');
        if (a == null || verb.isEmpty()) return;
        String s = a + " " + verb;
        if (!nothing(whom)) {
            String b = names.get(String.valueOf(whom));
            if (b == null) return;
            s += " " + b;
        }
        if (!maybe.contains(s)) maybe.add(s);
    }

    /** Without line breaks and the like, and not longer than the limit. */
    private static String clean(String s, int limit) {
        StringBuilder b = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            b.append(c < 32 ? ' ' : c);
        }
        String t = b.toString().trim();
        return t.length() > limit ? t.substring(0, limit) : t;
    }

    /** A value as a word: writing as it is, a whole number as its digits, anything else as nothing. */
    private static String word(Object v, int limit) {
        if (v instanceof String) return clean((String) v, limit);
        if (v instanceof Integer || v instanceof Long) return String.valueOf(v);
        return "";
    }

    private static double number(Object v) {
        return v instanceof Number ? ((Number) v).doubleValue() : 0;
    }

    /** How a thing looks: the values of its description (colour, clothing, posture...), in Mirage's order. */
    private static String looks(JSONObject o) {
        JSONObject d = o.optJSONObject("description");
        if (d == null) return "";
        StringBuilder b = new StringBuilder();
        for (Iterator<String> keys = d.keys(); keys.hasNext(); ) {
            String value = word(d.opt(keys.next()), 80);
            if (value.isEmpty()) continue;
            if (b.length() > 0) b.append(", ");
            b.append(value);
        }
        return b.toString();
    }

    /** Of Mirage's words for the place, what the box does not say already. */
    private static String position(String said) {
        if (said.isEmpty()) return "";
        boolean foreground = false, background = false;
        for (String w : said.toLowerCase(Locale.ROOT).split("\\s+")) {
            if (w.isEmpty()) continue;
            if (!PLAIN.contains(w)) return said;
            if (w.equals("foreground")) foreground = true;
            if (w.equals("background")) background = true;
        }
        return foreground ? "foreground" : background ? "background" : "";
    }

    /** Left, top, right, bottom on a scale of 0 to 1000; null when it is no box. */
    private static double[] box(JSONObject o) {
        JSONArray b = o.optJSONArray("bbox");
        if (b == null || b.length() < 4) return null;
        double[] box = new double[4];
        for (int i = 0; i < 4; i++) {
            Object x = b.opt(i);
            if (!(x instanceof Number)) return null;
            box[i] = Math.min(1000.0, Math.max(0.0, ((Number) x).doubleValue()));
        }
        return box[2] <= box[0] || box[3] <= box[1] ? null : box;
    }

    private static int third(double sum) {
        return sum * 3 < 2000 ? 0 : sum * 3 < 4000 ? 1 : 2;
    }

    private static int column(double[] b) {
        return third(b[0] + b[2]);
    }

    private static int row(double[] b) {
        return third(b[1] + b[3]);
    }

    private static String place(double[] b) {
        double w = b[2] - b[0], h = b[3] - b[1];
        if (w * h >= 600000) return "all over";
        if (w >= 800) return "across the " + ROWS[row(b)];
        if (h >= 800) return "down the " + COLUMNS[column(b)];
        return CELLS[row(b)][column(b)];
    }

    private static String share(double[] b) {
        int p = (int) ((b[2] - b[0]) * (b[3] - b[1]) / 10000.0 + 0.5);
        return p < 1 ? "under 1%" : p + "%";
    }

    private static String point(double[] b, int width, int height) {
        return "@" + (int) ((b[0] + b[2]) * width / 2000.0) + "," + (int) ((b[1] + b[3]) * height / 2000.0);
    }

    private static int percent(double score) {
        return (int) (score * 100 + 0.5);
    }
}
