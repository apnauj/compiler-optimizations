package optimizer;

/** Tiny ANSI helper so the live demo is readable on a projector. Set NO_COLOR=1 to disable. */
public final class Console {

    private static final String ESC = "\033";
    private static final boolean ON = System.getenv("NO_COLOR") == null;

    private Console() { }

    private static String wrap(String code, String s) { return ON ? ESC + code + s + ESC + "[0m" : s; }

    public static String bold(String s)   { return wrap("[1m",  s); }
    public static String dim(String s)    { return wrap("[90m", s); }
    public static String green(String s)  { return wrap("[92m", s); }
    public static String cyan(String s)   { return wrap("[96m", s); }
    public static String yellow(String s) { return wrap("[93m", s); }
    public static String red(String s)    { return wrap("[91m", s); }

    public static void rule(String title) {
        StringBuilder line = new StringBuilder();
        for (int i = 0; i < 72; i++) line.append('=');
        System.out.println();
        System.out.println(cyan(line.toString()));
        System.out.println(bold(cyan("  " + title)));
        System.out.println(cyan(line.toString()));
    }
}
