package com.jarvisos.app;

import android.app.AlarmManager;
import android.app.NotificationManager;
import android.content.Context;
import android.hardware.camera2.CameraManager;
import android.media.AudioManager;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.provider.Settings;
import android.text.format.DateFormat;

import java.util.HashMap;
import java.util.Map;

/**
 * What the built-in tools have done to the device, read from Android itself
 * rather than from Jarvis, for test mode: a tool can report success and still
 * leave the device unchanged, and this is how that shows. Keyed by tool name.
 */
final class DeviceState {

    private final Context mContext;
    private final CameraManager mCameras;
    /** Torch state per camera with a flash unit, as the camera service reports it. */
    private final Map<String, Boolean> mTorches = new HashMap<>();

    private final CameraManager.TorchCallback mTorchCallback = new CameraManager.TorchCallback() {
        @Override
        public void onTorchModeChanged(String cameraId, boolean enabled) {
            mTorches.put(cameraId, enabled);
        }

        @Override
        public void onTorchModeUnavailable(String cameraId) {
            mTorches.remove(cameraId);
        }
    };

    DeviceState(Context context) {
        mContext = context;
        mCameras = context.getSystemService(CameraManager.class);
    }

    /** Starts following the torch; the callback reports every torch at once, then each change. */
    void start(Handler handler) {
        if (mCameras != null) mCameras.registerTorchCallback(mTorchCallback, handler);
    }

    void stop() {
        if (mCameras != null) mCameras.unregisterTorchCallback(mTorchCallback);
        mTorches.clear();
    }

    /** The current state for each tool that changes something visible; tools not in the map have none. */
    Map<String, String> read() {
        Map<String, String> states = new HashMap<>();
        if (!mTorches.isEmpty()) {
            states.put("set_flashlight", mTorches.containsValue(true) ? "on" : "off");
        }

        NotificationManager notifications = mContext.getSystemService(NotificationManager.class);
        if (notifications != null) {
            switch (notifications.getCurrentInterruptionFilter()) {
                case NotificationManager.INTERRUPTION_FILTER_ALL:      states.put("set_dnd", "off"); break;
                case NotificationManager.INTERRUPTION_FILTER_PRIORITY: states.put("set_dnd", "priority"); break;
                case NotificationManager.INTERRUPTION_FILTER_ALARMS:   states.put("set_dnd", "alarms only"); break;
                case NotificationManager.INTERRUPTION_FILTER_NONE:     states.put("set_dnd", "total silence"); break;
                default: break;
            }
        }

        WifiManager wifi = mContext.getSystemService(WifiManager.class);
        if (wifi != null) states.put("toggle_wifi", wifi.isWifiEnabled() ? "on" : "off");
        states.put("toggle_bluetooth", Settings.Global.getInt(mContext.getContentResolver(),
                Settings.Global.BLUETOOTH_ON, 0) != 0 ? "on" : "off");

        // set_brightness writes level% of 255 and switches automatic brightness off.
        int brightness = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, -1);
        boolean automatic = Settings.System.getInt(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS_MODE, 0)
                == Settings.System.SCREEN_BRIGHTNESS_MODE_AUTOMATIC;
        if (brightness >= 0) {
            states.put("set_brightness", Math.round(brightness * 100f / 255) + "%"
                    + (automatic ? " auto" : ""));
        }

        AudioManager audio = mContext.getSystemService(AudioManager.class);
        if (audio != null) {
            int max = audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
            int now = audio.getStreamVolume(AudioManager.STREAM_MUSIC);
            states.put("set_volume", (max > 0 ? Math.round(now * 100f / max) : 0) + "%");
        }

        AlarmManager alarms = mContext.getSystemService(AlarmManager.class);
        AlarmManager.AlarmClockInfo next = alarms != null ? alarms.getNextAlarmClock() : null;
        states.put("set_alarm", next == null ? "none"
                : DateFormat.format(DateFormat.is24HourFormat(mContext) ? "EEE HH:mm" : "EEE h:mm a",
                        next.getTriggerTime()).toString());

        BatteryManager battery = mContext.getSystemService(BatteryManager.class);
        if (battery != null) {
            int level = battery.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY);
            if (level >= 0) states.put("get_battery_status", level + "%");
        }
        return states;
    }
}
