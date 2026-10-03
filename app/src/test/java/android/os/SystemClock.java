package android.os;

public final class SystemClock {
    private static long uptimeMillis;

    private SystemClock() {}

    public static long uptimeMillis() {
        return uptimeMillis;
    }

    static void advanceBy(long milliseconds) {
        uptimeMillis += milliseconds;
    }

    static void reset() {
        uptimeMillis = 0L;
    }
}
