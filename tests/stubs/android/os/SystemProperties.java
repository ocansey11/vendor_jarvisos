package android.os;

import java.util.HashMap;
import java.util.Map;

/** Stub: an in-memory property table. */
public class SystemProperties {
    private static final Map<String, String> sValues = new HashMap<>();
    public static String get(String key, String def) {
        String value = sValues.get(key);
        return value == null || value.isEmpty() ? def : value;
    }
    public static void set(String key, String value) { sValues.put(key, value); }
}
