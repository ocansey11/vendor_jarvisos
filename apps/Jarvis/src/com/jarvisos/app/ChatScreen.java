package com.jarvisos.app;

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

import java.util.ArrayList;
import java.util.List;

/**
 * The Chat tab: sends what the user types to JarvisManager.query and shows
 * the reply as it comes back, unedited. The conversation is kept in memory
 * only; the service keeps its own record.
 */
class ChatScreen {

    private static final class Message {
        final boolean fromUser;
        String text;
        boolean pending;
        boolean failed;

        Message(boolean fromUser, String text) {
            this.fromUser = fromUser;
            this.text = text;
        }
    }

    private final MainActivity mActivity;
    private final List<Message> mMessages = new ArrayList<>();
    private final EditText mInput;
    private final Button mSend;
    private final View mNotice;
    private final View mEmpty;

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

        mSend.setOnClickListener(v -> send());
        mInput.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN && !event.isShiftPressed();
            if (actionId != EditorInfo.IME_ACTION_SEND && !enter) return false;
            send();
            return true;
        });
    }

    /** Shows or hides the "no model loaded" notice. */
    void setModelReady(boolean ready) {
        mNotice.setVisibility(ready ? View.GONE : View.VISIBLE);
    }

    private void send() {
        final String query = mInput.getText().toString().trim();
        if (query.isEmpty() || !mSend.isEnabled()) return;
        mInput.setText("");
        mSend.setEnabled(false);

        final Message reply = new Message(false, mActivity.getString(R.string.chat_thinking));
        reply.pending = true;
        mMessages.add(new Message(true, query));
        mMessages.add(reply);
        mEmpty.setVisibility(View.GONE);
        mAdapter.notifyDataSetChanged();

        mActivity.call(jarvis -> jarvis.query(query), (result, error) -> {
            reply.pending = false;
            reply.failed = error != null;
            reply.text = error != null ? error : result;
            mSend.setEnabled(true);
            mAdapter.notifyDataSetChanged();
        });
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
            TextView text = view.findViewById(R.id.text);
            text.setText(message.text);
            text.setBackgroundResource(message.fromUser
                    ? R.drawable.bubble_user : R.drawable.bubble_jarvis);
            text.setTextColor(mActivity.getColor(message.fromUser ? R.color.on_accent
                    : message.failed ? R.color.error
                    : message.pending ? R.color.text_muted : R.color.text));
            ((FrameLayout.LayoutParams) text.getLayoutParams()).gravity =
                    message.fromUser ? Gravity.END : Gravity.START;
            text.requestLayout();
            return view;
        }
    };
}
