package com.nabd.hms.nursing;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * CLN-014: a finger signature as the pad sends it: SVG {@code <path d="M x y L x y ...">} strokes in a 600 x 200 box. Only the
 * path coordinates are kept; the stored SVG is rebuilt from them, so nothing else the client sent (scripts, links, styles) is kept.
 */
final class ConsentSignature {

    static final float WIDTH = 600f;
    static final float HEIGHT = 200f;
    private static final int MAX_POINTS = 20000;
    private static final Pattern PATH_D = Pattern.compile("\\bd\\s*=\\s*\"([^\"]*)\"");
    private static final Pattern ALLOWED = Pattern.compile("[MLml0-9.,\\s-]*");
    private static final Pattern TOKEN = Pattern.compile("[MLml]|-?\\d+(?:\\.\\d+)?");

    private ConsentSignature() {
    }

    /** Strokes of (x, y) points; empty when the SVG has no drawable stroke. Throws IllegalArgumentException on bad path data. */
    static List<List<float[]>> parse(String svg) {
        List<List<float[]>> strokes = new ArrayList<>();
        if (svg == null) return strokes;
        int points = 0;
        Matcher m = PATH_D.matcher(svg);
        while (m.find()) {
            String d = m.group(1);
            if (!ALLOWED.matcher(d).matches()) throw new IllegalArgumentException("path data may only hold M/L commands and numbers");
            Matcher t = TOKEN.matcher(d);
            List<float[]> stroke = null;
            Float x = null;
            while (t.find()) {
                String tok = t.group();
                if (tok.equalsIgnoreCase("M")) {
                    if (stroke != null && !stroke.isEmpty()) strokes.add(stroke);
                    stroke = new ArrayList<>();
                    x = null;
                } else if (tok.equalsIgnoreCase("L")) {
                    if (stroke == null) throw new IllegalArgumentException("a stroke must start with M");
                } else {
                    if (stroke == null) throw new IllegalArgumentException("a stroke must start with M");
                    float v = Float.parseFloat(tok);
                    if (x == null) {
                        x = Math.max(0, Math.min(WIDTH, v));
                    } else {
                        stroke.add(new float[]{x, Math.max(0, Math.min(HEIGHT, v))});
                        x = null;
                        if (++points > MAX_POINTS) throw new IllegalArgumentException("signature has too many points");
                    }
                }
            }
            if (stroke != null && !stroke.isEmpty()) strokes.add(stroke);
        }
        return strokes;
    }

    static String toSvg(List<List<float[]>> strokes) {
        StringBuilder b = new StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 600 200\" width=\"600\" height=\"200\">");
        for (List<float[]> s : strokes) {
            b.append("<path fill=\"none\" stroke=\"#000\" stroke-width=\"2\" stroke-linecap=\"round\" d=\"");
            for (int i = 0; i < s.size(); i++) {
                b.append(i == 0 ? "M" : " L").append(n(s.get(i)[0])).append(' ').append(n(s.get(i)[1]));
            }
            b.append("\"/>");
        }
        return b.append("</svg>").toString();
    }

    private static String n(float f) {
        return String.format(Locale.ROOT, "%.1f", f);
    }
}
