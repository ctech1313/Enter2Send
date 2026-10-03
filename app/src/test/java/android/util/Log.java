package android.util;

import java.util.ArrayList;
import java.util.List;

public final class Log {
    public static final List<String> messagesForTest = new ArrayList<>();

    private Log() {}

    public static int d(String tag, String message) {
        messagesForTest.add(message);
        return 0;
    }
}
