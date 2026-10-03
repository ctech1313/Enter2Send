package android.content;

import java.util.HashMap;
import java.util.Map;

/** Minimal in-memory Context ABI used by local JVM service tests. */
public class Context {
    public static final int MODE_PRIVATE = 0;

    private final Map<String, InMemorySharedPreferences> preferences = new HashMap<>();

    public SharedPreferences getSharedPreferences(String name, int mode) {
        return preferences.computeIfAbsent(name, ignored -> new InMemorySharedPreferences());
    }

    private static final class InMemorySharedPreferences implements SharedPreferences {
        private final Map<String, Boolean> booleans = new HashMap<>();

        @Override
        public boolean getBoolean(String key, boolean defaultValue) {
            return booleans.getOrDefault(key, defaultValue);
        }

        @Override
        public Editor edit() {
            return new Editor() {
                @Override
                public Editor putBoolean(String key, boolean value) {
                    booleans.put(key, value);
                    return this;
                }

                @Override
                public void apply() {
                    // Writes are synchronous in this deterministic test fake.
                }
            };
        }
    }
}
