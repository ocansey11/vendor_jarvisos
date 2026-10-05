package com.jarvisos.app;

import android.app.Activity;
import android.jarvis.JarvisException;
import android.jarvis.JarvisManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The on-screen way to talk to the Jarvis system service: a Chat tab
 * (JarvisManager.query) and a Tools tab (the tool registry, with a form to
 * run any tool by hand). Holds no logic of its own; everything is a call to
 * the service.
 */
public class MainActivity extends Activity {

    private static final String TAG = "JarvisApp";

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

    private JarvisManager mJarvis;
    private TextView mStatus;
    private View mChatTab, mToolsTab, mChatPane, mToolsPane;
    private ChatScreen mChat;
    private ToolsScreen mTools;

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
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        mWorker.shutdownNow();
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
            try {
                JSONObject status = new JSONObject(json);
                boolean model = status.getJSONObject("models").optBoolean("primary");
                mChat.setModelReady(model);
                mStatus.setText(!status.optBoolean("ready")
                        ? getString(R.string.status_starting)
                        : getString(R.string.status_ready, status.optInt("tools"),
                                getString(model ? R.string.status_model_ready
                                        : R.string.status_model_missing)));
            } catch (JSONException e) {
                Log.w(TAG, "Unreadable status: " + json, e);
                mStatus.setText(R.string.status_unavailable);
            }
        });
        mTools.load();
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
