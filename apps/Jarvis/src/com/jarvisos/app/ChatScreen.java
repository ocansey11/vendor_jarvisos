package com.jarvisos.app;

import android.jarvis.JarvisManager;
import android.util.Log;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The Chat tab: sends what the user types to JarvisManager.query, or what
 * they say to JarvisManager.queryWithAudio, and shows the reply as it comes
 * back, unedited. The conversation is kept in memory
 * only; the service keeps its own record. In test mode each reply also shows
 * the service's trace of it: Needle's choice, the tools that ran, the times.
 */
class ChatScreen {

    private static final String TAG = "JarvisApp";

    private static final class Message {
        final boolean fromUser;
        String text;
        boolean pending;
        boolean failed;
        /** Test mode: what happened inside this reply; null until it is known. */
        String trace;

        Message(boolean fromUser, String text) {
            this.fromUser = fromUser;
            this.text = text;
        }
    }

    private final MainActivity mActivity;
    private final List<Message> mMessages = new ArrayList<>();
    private final EditText mInput;
    private final Button mSend;
    private final Button mSpeak;
    private final VoiceRecorder mRecorder = new VoiceRecorder();
    private final View mNotice;
    private final View mEmpty;
    private boolean mTestMode;

    ChatScreen(MainActivity activity) {
        mActivity = activity;
        mInput  = activity.findViewById(R.id.chat_input);
        mSend   = activity.findViewById(R.id.chat_send);
        mEmpty  = activity.findViewById(R.id.chat_empty);
        ListView list = activity.findViewById(R.id.chat_list);
        View header = activity.getLayoutInflater().inflate(R.layout.item_chat_notice, list, false);
        mNotice = header.findViewById(R.id.chat_notice);
        list.addHeaderView(header, null, false);
        list.setAdapter(mAdapter);

        mSpeak  = activity.findViewById(R.id.chat_speak);

        mSend.setOnClickListener(v -> send());
        mSpeak.setOnClickListener(v -> speak());
        mInput.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN && !event.isShiftPressed();
            if (actionId != EditorInfo.IME_ACTION_SEND && !enter) return false;
            send();
            return true;
        });
    }

    void setTestMode(boolean on) {
        mTestMode = on;
        mAdapter.notifyDataSetChanged();
    }

    /** Shows or hides the "no model loaded" notice. */
    void setModelReady(boolean ready) {
        mNotice.setVisibility(ready ? View.GONE : View.VISIBLE);
    }

    private void send() {
        final String query = mInput.getText().toString().trim();
        if (query.isEmpty() || !mSend.isEnabled()) return;
        mInput.setText("");
        final Message reply = ask(query);
        final Message request = mMessages.get(mMessages.size() - 2);
        mActivity.call(jarvis -> jarvis.query(query), (result, error) -> {
            show(reply, result, error);
            if (mTestMode && error == null) fetchTrace(request, reply);
        });
    }

    /**
     * Test mode: reads the service's trace of the request just answered and
     * puts it under the reply. For a spoken request, the user's bubble then
     * shows what was heard.
     */
    private void fetchTrace(Message request, Message reply) {
        mActivity.call(JarvisManager::getStatus, (json, error) -> {
            if (error != null) return;
            try {
                JSONObject trace = new JSONObject(json).optJSONObject("last");
                if (trace == null) return;
                if (trace.optBoolean("spoken")) {
                    String heard = trace.optString("request");
                    if (!heard.isEmpty()) request.text = "🎤 " + heard;
                } else if (!trace.optString("request").equals(request.text)) {
                    return;   // someone else's request (the shell) came in between
                }
                reply.trace = describe(trace);
                mAdapter.notifyDataSetChanged();
            } catch (JSONException e) {
                Log.w(TAG, "Unreadable trace", e);
            }
        });
    }

    /** The trace as a few short lines: who decided, which tools ran with what, how long. */
    private String describe(JSONObject trace) throws JSONException {
        StringBuilder out = new StringBuilder();
        JSONObject needle = trace.optJSONObject("needle");
        final boolean fast = "fast".equals(trace.optString("path"));
        if (needle != null) {
            double confidence = needle.optDouble("confidence");
            double bar = needle.optDouble("bar");
            out.append(fast
                    ? mActivity.getString(R.string.trace_needle_ran, confidence, bar,
                            needle.optLong("ms"))
                    : mActivity.getString(R.string.trace_needle_passed, confidence, bar,
                            needle.optString("calls").isEmpty()
                                    ? mActivity.getString(R.string.trace_no_tool)
                                    : needle.optString("calls")));
        }
        if (!fast) {
            if (out.length() > 0) out.append('\n');
            out.append(mActivity.getString(R.string.trace_model, trace.optString("model")));
        }
        JSONArray tools = trace.optJSONArray("tools");
        if (tools == null || tools.length() == 0) {
            if (!fast) out.append(" · ").append(mActivity.getString(R.string.trace_no_tool));
        } else {
            for (int i = 0; i < tools.length(); i++) {
                JSONObject tool = tools.getJSONObject(i);
                out.append("\n  → ").append(tool.optString("name")).append(' ')
                        .append(tool.optString("args"))
                        .append("\n    ").append(tool.optString("result"));
            }
        }
        out.append('\n').append(mActivity.getString(R.string.trace_total,
                trace.optLong("ms") / 1000.0));
        return out.toString();
    }

    /** First tap starts listening; it stops by itself after the speaker does, or on a second tap. */
    private void speak() {
        if (mRecorder.isRecording()) {
            mRecorder.stop();
            return;
        }
        if (!mSend.isEnabled()) return;
        if (mActivity.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO)
                != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            mActivity.requestPermissions(
                    new String[] {android.Manifest.permission.RECORD_AUDIO}, 0);
            return;
        }
        final Message reply = ask(mActivity.getString(R.string.chat_spoken));
        final Message request = mMessages.get(mMessages.size() - 2);
        reply.text = mActivity.getString(R.string.chat_listening);
        mSpeak.setText(R.string.chat_stop);
        mAdapter.notifyDataSetChanged();
        mRecorder.start(pcm -> {
            mSpeak.setText(R.string.chat_speak);
            if (pcm == null) {
                show(reply, null, mActivity.getString(R.string.chat_no_microphone));
                return;
            }
            reply.text = mActivity.getString(R.string.chat_thinking);
            mAdapter.notifyDataSetChanged();
            mActivity.call(jarvis -> jarvis.queryWithAudio("", pcm), (result, error) -> {
                show(reply, result, error);
                if (mTestMode && error == null) fetchTrace(request, reply);
            });
        });
    }

    /** Adds the user's request and a pending reply to the conversation. */
    private Message ask(String request) {
        mSend.setEnabled(false);
        final Message reply = new Message(false, mActivity.getString(R.string.chat_thinking));
        reply.pending = true;
        mMessages.add(new Message(true, request));
        mMessages.add(reply);
        mEmpty.setVisibility(View.GONE);
        mAdapter.notifyDataSetChanged();
        return reply;
    }

    private void show(Message reply, String result, String error) {
        reply.pending = false;
        reply.failed = error != null;
        reply.text = error != null ? error : result;
        mSend.setEnabled(true);
        mAdapter.notifyDataSetChanged();
    }

    private final BaseAdapter mAdapter = new BaseAdapter() {
        @Override public int getCount() { return mMessages.size(); }
        @Override public Object getItem(int position) { return mMessages.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public boolean isEnabled(int position) { return false; }

        @Override
        public View getView(int position, View view, ViewGroup parent) {
            if (view == null) {
                view = mActivity.getLayoutInflater().inflate(R.layout.item_message, parent, false);
            }
            Message message = mMessages.get(position);
            TextView trace = view.findViewById(R.id.trace);
            trace.setText(message.trace);
            trace.setVisibility(mTestMode && message.trace != null ? View.VISIBLE : View.GONE);
            TextView text = view.findViewById(R.id.text);
            text.setText(message.text);
            text.setBackgroundResource(message.fromUser
                    ? R.drawable.bubble_user : R.drawable.bubble_jarvis);
            text.setTextColor(mActivity.getColor(message.fromUser ? R.color.on_accent
                    : message.failed ? R.color.error
                    : message.pending ? R.color.text_muted : R.color.text));
            View column = view.findViewById(R.id.column);
            ((FrameLayout.LayoutParams) column.getLayoutParams()).gravity =
                    message.fromUser ? Gravity.END : Gravity.START;
            column.requestLayout();
            return view;
        }
    };
}
