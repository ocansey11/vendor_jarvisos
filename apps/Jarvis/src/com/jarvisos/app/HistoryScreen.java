package com.jarvisos.app;

import android.app.AlertDialog;
import android.graphics.Typeface;
import android.jarvis.JarvisManager;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.format.DateFormat;
import android.text.format.DateUtils;
import android.text.style.ForegroundColorSpan;
import android.text.style.StyleSpan;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;

/**
 * The History tab: the conversations the service has kept, chats and app
 * sessions (a game of tic-tac-toe with Jarvis) in one list, newest first.
 * Touching one shows it as a transcript: what the person said or did, the
 * tools Jarvis ran, the lines it said. The service forgets each conversation
 * after a day (persist.sys.jarvis.history_hours); what Jarvis learned from it
 * stays in its memory.
 */
class HistoryScreen {

    private static final String TAG = "JarvisApp";

    private final MainActivity mActivity;
    private final List<JSONObject> mConversations = new ArrayList<>();
    private final TextView mNote;
    private final TextView mEmpty;

    HistoryScreen(MainActivity activity) {
        mActivity = activity;
        mNote = activity.findViewById(R.id.history_note);
        mEmpty = activity.findViewById(R.id.history_empty);
        mNote.setText(activity.getString(R.string.history_note, 24));
        ListView list = activity.findViewById(R.id.history_list);
        list.setAdapter(mAdapter);
        list.setOnItemClickListener((parent, view, position, id) ->
                open(mConversations.get(position).optLong("id")));
        activity.findViewById(R.id.history_clear).setOnClickListener(v ->
                new AlertDialog.Builder(activity)
                        .setMessage(R.string.history_clear_confirm)
                        .setPositiveButton(R.string.history_clear, (d, w) -> clear())
                        .setNegativeButton(android.R.string.cancel, null)
                        .show());
    }

    /** Re-reads the list from the service. */
    void load() {
        mActivity.call(JarvisManager::listConversations, (json, error) -> {
            mConversations.clear();
            if (error == null) {
                try {
                    JSONObject result = new JSONObject(json);
                    mNote.setText(mActivity.getString(R.string.history_note,
                            result.optInt("keepHours", 24)));
                    JSONArray list = result.optJSONArray("conversations");
                    for (int i = 0; list != null && i < list.length(); i++) {
                        mConversations.add(list.getJSONObject(i));
                    }
                } catch (JSONException e) {
                    Log.w(TAG, "Unreadable history", e);
                    error = String.valueOf(e.getMessage());
                }
            }
            mEmpty.setText(error != null ? error : mActivity.getString(R.string.history_empty));
            mEmpty.setVisibility(mConversations.isEmpty() ? View.VISIBLE : View.GONE);
            mAdapter.notifyDataSetChanged();
        });
    }

    private void clear() {
        mActivity.call(jarvis -> {
            jarvis.clearHistory();
            return "";
        }, (ok, error) -> load());
    }

    /** Fetches one conversation and shows it as a transcript. */
    private void open(long id) {
        mActivity.call(jarvis -> jarvis.getConversation(id), (json, error) -> {
            CharSequence text;
            String title = null;
            if (error != null) {
                text = error;
            } else {
                try {
                    JSONObject conversation = new JSONObject(json);
                    title = conversation.optString("title", null);
                    text = conversation.has("messages")
                            ? transcript(conversation)
                            : mActivity.getString(R.string.history_gone);
                } catch (JSONException e) {
                    text = String.valueOf(e.getMessage());
                }
            }
            TextView body = new TextView(mActivity);
            body.setText(text);
            body.setTextIsSelectable(true);
            body.setTextSize(14);
            body.setLineSpacing(0, 1.15f);
            body.setTextColor(mActivity.getColor(R.color.text));
            int pad = Math.round(20 * mActivity.getResources().getDisplayMetrics().density);
            body.setPadding(pad, pad / 2, pad, pad / 2);
            ScrollView scroll = new ScrollView(mActivity);
            scroll.addView(body);
            new AlertDialog.Builder(mActivity)
                    .setTitle(title)
                    .setView(scroll)
                    .setPositiveButton(R.string.history_close, null)
                    .show();
            if (title == null) load();   // it was forgotten: refresh the list
        });
    }

    /**
     * One line per message: "You", "Jarvis" and the app name in bold, tool
     * calls and app events in the muted colour, and the time on each line.
     */
    private CharSequence transcript(JSONObject conversation) {
        SpannableStringBuilder out = new SpannableStringBuilder();
        boolean session = "session".equals(conversation.optString("mode"));
        String app = session ? appName(conversation.optString("title"))
                : mActivity.getString(R.string.history_app);
        int muted = mActivity.getColor(R.color.text_muted);
        JSONArray messages = conversation.optJSONArray("messages");
        for (int i = 0; messages != null && i < messages.length(); i++) {
            JSONObject m = messages.optJSONObject(i);
            if (m == null) continue;
            String source = m.optString("source");
            String content = m.optString("content");
            if (out.length() > 0) out.append('\n');
            int lineStart = out.length();
            out.append(DateFormat.getTimeFormat(mActivity).format(new Date(m.optLong("timestamp"))))
                    .append("  ");
            out.setSpan(new ForegroundColorSpan(muted), lineStart, out.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
            switch (source) {
                case "tool": {
                    label(out, mActivity.getString(R.string.history_jarvis));
                    String said = saidBy(content);
                    if (said != null) {
                        out.append('“').append(said).append('”');
                    } else {
                        muted(out, "⚙ " + shortCall(content), muted);
                    }
                    break;
                }
                case "event":
                    label(out, app);
                    muted(out, event(content), muted);
                    break;
                case "say":
                    label(out, mActivity.getString(R.string.history_jarvis));
                    out.append('“').append(content).append('”');
                    break;
                case "end":
                    muted(out, "— " + content + " —", muted);
                    break;
                default:
                    label(out, mActivity.getString(m.optBoolean("isUser")
                            ? R.string.history_you : R.string.history_jarvis));
                    out.append(content);
            }
        }
        return out;
    }

    private static void label(SpannableStringBuilder out, String who) {
        int start = out.length();
        out.append(who).append(": ");
        out.setSpan(new StyleSpan(Typeface.BOLD), start, out.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    private static void muted(SpannableStringBuilder out, String text, int colour) {
        int start = out.length();
        out.append(text);
        out.setSpan(new ForegroundColorSpan(colour), start, out.length(),
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
    }

    /** The line in a say call ("say(text=…) → Said to the user: …"), or null for other tools. */
    private static String saidBy(String toolLine) {
        if (!toolLine.startsWith("say(")) return null;
        int said = toolLine.indexOf("→ Said to the user: ");
        if (said >= 0) return toolLine.substring(said + "→ Said to the user: ".length());
        return toolLine.startsWith("say(text=") && toolLine.endsWith(")")
                ? toolLine.substring("say(text=".length(), toolLine.length() - 1) : null;
    }

    /** A tool line with its result cut short; the service keeps it whole. */
    private static String shortCall(String toolLine) {
        int arrow = toolLine.indexOf(" → ");
        if (arrow < 0 || toolLine.length() - arrow < 90) return toolLine;
        return toolLine.substring(0, arrow + 3 + 80) + "…";
    }

    /**
     * A session's preview in plain words. The last event is the app's data,
     * written for Jarvis ("you" is Jarvis, "player" the person), so a result
     * reads "Jarvis won", "You won" or "Draw"; any other event shows its name.
     */
    private static String sessionPreview(String preview) {
        int space = preview.indexOf(' ');
        if (space < 0 || !preview.substring(space + 1).startsWith("{")) return preview;
        String name = preview.substring(0, space).replace('_', ' ');
        int at = preview.indexOf("\"winner\":\"");
        if (at < 0) return name;
        String winner = preview.substring(at + 10);
        winner = winner.substring(0, Math.max(0, winner.indexOf('"')));
        switch (winner) {
            case "you": return name + " · Jarvis won";
            case "player": return name + " · you won";
            case "draw": return name + " · draw";
            default: return name;
        }
    }

    /** "player_moved {"cell":0,…}" → "player_moved · cell 0, board X........". */
    private static String event(String content) {
        int space = content.indexOf(' ');
        if (space < 0) return content;
        String name = content.substring(0, space);
        try {
            JSONObject data = new JSONObject(content.substring(space + 1));
            if (data.length() == 0) return name;
            StringBuilder text = new StringBuilder(name).append(" · ");
            boolean first = true;
            for (Iterator<String> keys = data.keys(); keys.hasNext(); ) {
                String key = keys.next();
                if (!first) text.append(", ");
                first = false;
                text.append(key.replace('_', ' ')).append(' ').append(data.opt(key));
            }
            return text.toString();
        } catch (JSONException e) {
            return content;
        }
    }

    /** "Tic-tac-toe with Jarvis" → "Tic-tac-toe". */
    private static String appName(String title) {
        return title.endsWith(" with Jarvis")
                ? title.substring(0, title.length() - " with Jarvis".length()) : title;
    }

    private final BaseAdapter mAdapter = new BaseAdapter() {
        @Override public int getCount() { return mConversations.size(); }
        @Override public Object getItem(int position) { return mConversations.get(position); }
        @Override public long getItemId(int position) { return mConversations.get(position).optLong("id"); }

        @Override
        public View getView(int position, View view, ViewGroup parent) {
            if (view == null) {
                view = mActivity.getLayoutInflater().inflate(R.layout.item_history, parent, false);
            }
            JSONObject c = mConversations.get(position);
            ((TextView) view.findViewById(R.id.title)).setText(c.optString("title"));
            TextView tag = view.findViewById(R.id.tag);
            String mode = c.optString("mode");
            tag.setText("session".equals(mode) ? R.string.history_tag_session
                    : R.string.history_tag_voice);
            tag.setVisibility("session".equals(mode) || "voice".equals(mode)
                    ? View.VISIBLE : View.GONE);
            CharSequence when = DateUtils.getRelativeTimeSpanString(c.optLong("lastMessageAt"),
                    System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS);
            String preview = "session".equals(mode)
                    ? sessionPreview(c.optString("preview")) : c.optString("preview");
            ((TextView) view.findViewById(R.id.subtitle)).setText(
                    mActivity.getString(R.string.history_count, when, c.optInt("messages"))
                    + (preview.isEmpty() ? "" : "\n" + preview));
            return view;
        }
    };
}
