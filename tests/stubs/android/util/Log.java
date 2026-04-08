package android.util;

/** Stub — replaces android.util.Log for JVM unit tests. All calls are no-ops. */
public final class Log {
    public static int d(String tag, String msg) { return 0; }
    public static int d(String tag, String msg, Throwable t) { return 0; }
    public static int i(String tag, String msg) { return 0; }
    public static int i(String tag, String msg, Throwable t) { return 0; }
    public static int w(String tag, String msg) { return 0; }
    public static int w(String tag, String msg, Throwable t) { return 0; }
    public static int e(String tag, String msg) { return 0; }
    public static int e(String tag, String msg, Throwable t) { return 0; }
    private Log() {}
}
