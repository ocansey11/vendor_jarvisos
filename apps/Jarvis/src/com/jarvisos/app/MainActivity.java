package com.jarvisos.app;

import android.app.Activity;
import android.content.SharedPreferences;
import android.jarvis.JarvisException;
import android.jarvis.JarvisManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Switch;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The on-screen way to talk to the Jarvis system service: a Chat tab
 * (JarvisManager.query) and a Tools tab (the tool registry, with a form to
 * run any tool by hand). Holds no logic of its own; everything is a call to
 * the service.
 *
 * Test mode (the switch in the header) is for recording and comparing models:
 * the status line names the engine, chat model and fast path, each reply shows
 * what happened inside it, and the Tools tab shows what each tool has done to
 * the device, with a Reset to a known start.
 */
public class MainActivity extends Activity {

    private static final String TAG = "JarvisApp";
    private static final String PREF_TEST_MODE = "test_mode";
    /** How often test mode re-reads the service status and the device state. */
    private static final long POLL_MS = 1_000;

    /** Runs one service call and hands back what to show. Never called on the main thread. */
    interface Call {
        String run(JarvisManager jarvis) throws JarvisException;
    }

    /** Receives the outcome on the main thread; exactly one argument is non-null. */
    interface Reply {
        void done(String result, String error);
    }

    private final Handler mMain = new Handler(Looper.getMainLooper());
    // One thread: service calls from this app run in the order they were made.
    private final ExecutorService mWorker = Executors.newSingleThreadExecutor();
    // Test mode's status polls, on their own thread so they never wait behind
    // a query that takes the chat model half a minute.
    private final ExecutorService mPoller = Executors.newSingleThreadExecutor();
    private final AtomicBoolean mPollPending = new AtomicBoolean();
    private final Runnable mPoll = new Runnable() {
        @Override
        public void run() {
            pollStatus();
            mTools.showStates();
            mMain.postDelayed(this, POLL_MS);
        }
    };

    private JarvisManager mJarvis;
    private TextView mStatus;
    private View mChatTab, mToolsTab, mChatPane, mToolsPane;
    private ChatScreen mChat;
    private ToolsScreen mTools;
    private SharedPreferences mPrefs;
    private boolean mTestMode;
    private boolean mResumed;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mJarvis = new JarvisManager(this);

        mStatus    = findViewById(R.id.status);
        mChatTab   = findViewById(R.id.tab_chat);
        mToolsTab  = findViewById(R.id.tab_tools);
        mChatPane  = findViewById(R.id.chat);
        mToolsPane = findViewById(R.id.tools);
        mChat  = new ChatScreen(this);
        mTools = new ToolsScreen(this);

        mChatTab.setOnClickListener(v -> showTab(false));
        mToolsTab.setOnClickListener(v -> showTab(true));
        showTab(false);

        mPrefs = getSharedPreferences("jarvis", MODE_PRIVATE);
        mTestMode = mPrefs.getBoolean(PREF_TEST_MODE, false);
        Switch test = findViewById(R.id.test_mode);
        test.setChecked(mTestMode);
        test.setOnCheckedChangeListener((v, on) -> setTestMode(on));
        mChat.setTestMode(mTestMode);
        mTools.setTestMode(mTestMode);
    }

    @Override
    protected void onResume() {
        super.onResume();
        mResumed = true;
        refresh();
        updatePolling();
    }

    @Override
    protected void onPause() {
        super.onPause();
        mResumed = false;
        updatePolling();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mWorker.shutdownNow();
        mPoller.shutdownNow();
    }

    boolean isTestMode() {
        return mTestMode;
    }

    private void setTestMode(boolean on) {
        mTestMode = on;
        mPrefs.edit().putBoolean(PREF_TEST_MODE, on).apply();
        mChat.setTestMode(on);
        mTools.setTestMode(on);
        updatePolling();
        refresh();
    }

    /** Test mode polls only while the app is on screen. */
    private void updatePolling() {
        mMain.removeCallbacks(mPoll);
        if (mTestMode && mResumed) {
            mTools.startStates();
            mMain.post(mPoll);
        } else {
            mTools.stopStates();
        }
    }

    /** Re-reads the status for the status line, unless a read is already on its way. */
    private void pollStatus() {
        if (!mPollPending.compareAndSet(false, true)) return;
        mPoller.execute(() -> {
            String json = null;
            try {
                json = mJarvis.getStatus();
            } catch (JarvisException | RuntimeException e) {
                Log.w(TAG, "Status poll failed", e);
            }
            final String status = json;
            mMain.post(() -> {
                mPollPending.set(false);
                if (!isDestroyed() && status != null) showStatus(status);
            });
        });
    }

    private void showTab(boolean tools) {
        mChatTab.setSelected(!tools);
        mToolsTab.setSelected(tools);
        mChatPane.setVisibility(tools ? View.GONE : View.VISIBLE);
        mToolsPane.setVisibility(tools ? View.VISIBLE : View.GONE);
    }

    /** Re-reads the service state and the tool list. */
    void refresh() {
        call(JarvisManager::getStatus, (json, error) -> {
            if (error != null) {
                mStatus.setText(error);
                mChat.setModelReady(true);   // the status line already says what is wrong
                return;
            }
            showStatus(json);
        });
        mTools.load();
    }

    private void showStatus(String json) {
        try {
            JSONObject status = new JSONObject(json);
            mTools.showSessions(status.optJSONObject("sessions"));
            boolean model = status.getJSONObject("models").optBoolean("primary");
            mChat.setModelReady(model);
            if (!status.optBoolean("ready")) {
                mStatus.setText(R.string.status_starting);
            } else if (mTestMode) {
                mStatus.setText(getString(R.string.status_test, status.optString("engine"),
                        model ? status.optString("chatModel")
                                : getString(R.string.status_model_missing),
                        status.optBoolean("fastPath")
                                ? getString(R.string.status_fast_on, status.optDouble("fastBar"))
                                : getString(R.string.status_fast_off)));
            } else {
                mStatus.setText(getString(R.string.status_ready, status.optInt("tools"),
                        getString(model ? R.string.status_model_ready
                                : R.string.status_model_missing)));
            }
        } catch (JSONException e) {
            Log.w(TAG, "Unreadable status: " + json, e);
            mStatus.setText(R.string.status_unavailable);
        }
    }

    /** Runs a service call off the main thread and delivers the outcome on it. */
    void call(Call call, Reply reply) {
        mWorker.execute(() -> {
            String result = null;
            String error = null;
            try {
                result = call.run(mJarvis);
                if (result == null) error = getString(R.string.status_unavailable);
            } catch (JarvisException e) {
                Log.w(TAG, "Jarvis service call failed", e);
                error = getString(R.string.status_unavailable);
            } catch (SecurityException e) {
                Log.w(TAG, "Jarvis service refused the call", e);
                error = getString(R.string.status_denied);
            } catch (RuntimeException e) {
                Log.w(TAG, "Jarvis service call failed", e);
                error = String.valueOf(e.getMessage());
            }
            final String r = error == null ? result : null;
            final String e = error;
            mMain.post(() -> {
                if (!isDestroyed()) reply.done(r, e);
            });
        });
    }
}
