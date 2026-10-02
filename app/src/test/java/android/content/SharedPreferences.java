package android.content;

/** The subset of SharedPreferences called by production BridgePreferences. */
public interface SharedPreferences {
    boolean getBoolean(String key, boolean defaultValue);

    Editor edit();

    interface Editor {
        Editor putBoolean(String key, boolean value);

        void apply();
    }
}
