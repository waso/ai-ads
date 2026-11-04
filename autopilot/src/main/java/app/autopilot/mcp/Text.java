package app.autopilot.mcp;

/** Makes free text safe to hand to Claude as data: no control characters, no line breaks, bounded length. */
final class Text {
    private Text() {}

    static String untrusted(String s) {
        return untrusted(s, 200);
    }

    static String untrusted(String s, int max) {
        if (s == null) return null;
        String t = s.replaceAll("\\p{Cntrl}+", " ").replaceAll("\\s{2,}", " ").strip();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    static String capped(String s, int max) {
        if (s == null) return null;
        String t = s.strip();
        return t.length() <= max ? t : t.substring(0, max);
    }
}
