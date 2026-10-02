package com.jarvisos.demo.alarms;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Map;
import java.util.TreeMap;

/**
 * The app's own alarm list: time ("07:00") to label. This is the single source
 * of truth the tools read and write, so "is there already an alarm?" is always
 * answered from current state, however the alarm got there.
 */
final class AlarmStore {
    private final SharedPreferences mPrefs;

    AlarmStore(Context context) {
        mPrefs = context.getSharedPreferences("alarms", Context.MODE_PRIVATE);
    }

    static String key(int hour, int minute) {
        return String.format("%02d:%02d", hour, minute);
    }

    Map<String, String> all() {
        Map<String, String> sorted = new TreeMap<>();
        for (Map.Entry<String, ?> e : mPrefs.getAll().entrySet()) {
            sorted.put(e.getKey(), String.valueOf(e.getValue()));
        }
        return sorted;
    }

    /** @return false if an alarm for that time already existed (nothing changed). */
    boolean add(int hour, int minute, String label) {
        String key = key(hour, minute);
        if (mPrefs.contains(key)) return false;
        mPrefs.edit().putString(key, label != null ? label : "").commit();
        return true;
    }

    /** @return false if there was no alarm for that time (nothing changed). */
    boolean remove(int hour, int minute) {
        String key = key(hour, minute);
        if (!mPrefs.contains(key)) return false;
        mPrefs.edit().remove(key).commit();
        return true;
    }
}
