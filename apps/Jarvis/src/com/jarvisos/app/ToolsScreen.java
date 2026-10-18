package com.jarvisos.app;

import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.jarvis.JarvisManager;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseExpandableListAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.ExpandableListView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The Tools tab: the apps that own tools, each opening to show its tools, the
 * way the registry stores them (one AppRecord, many ToolRecords). Tapping a
 * tool opens a form built from its declared parameters; Run calls the tool
 * through the service, with no model involved, and shows its reply.
 *
 * In test mode each tool that changes the device shows its current state, read
 * from Android, and Reset puts those back to a known start through the same
 * tools.
 */
class ToolsScreen {

    private static final String TAG = "JarvisApp";

    /** Reset: the calls that put the device back to the start the reset bar describes. */
    private static final String[][] RESET = {
        {"set_flashlight",   "{\"enabled\":false}"},
        {"set_dnd",          "{\"mode\":\"off\"}"},
        {"toggle_wifi",      "{\"enabled\":false}"},
        {"toggle_bluetooth", "{\"enabled\":false}"},
        {"set_brightness",   "{\"level\":30}"},
        {"set_volume",       "{\"level\":30}"},
    };

    private final MainActivity mActivity;
    /** One entry per owning app, each holding that app's tools. */
    private final List<List<Tool>> mApps = new ArrayList<>();
    private final TextView mEmpty;
    private final ExpandableListView mList;
    private final View mResetBar;
    private final TextView mResetText;
    private final Button mReset;
    private final DeviceState mDevice;
    private boolean mTestMode;
    private boolean mFollowing;
    /** Test mode: tool name to its current state; empty otherwise. */
    private Map<String, String> mStates = Collections.emptyMap();
    /** Test mode: the service's session record (declared, open, last), or null. */
    private JSONObject mSessions;

    ToolsScreen(MainActivity activity) {
        mActivity = activity;
        mEmpty = activity.findViewById(R.id.tools_empty);
        mResetBar  = activity.findViewById(R.id.tools_reset_bar);
        mResetText = activity.findViewById(R.id.tools_reset_text);
        mReset     = activity.findViewById(R.id.tools_reset);
        mReset.setOnClickListener(v -> reset());
        mDevice = new DeviceState(activity);
        mList = activity.findViewById(R.id.tools_list);
        mList.setAdapter(mAdapter);
        mList.setOnChildClickListener((parent, view, app, tool, id) -> {
            open(mApps.get(app).get(tool));
            return true;
        });
    }

    void setTestMode(boolean on) {
        mTestMode = on;
        mResetBar.setVisibility(on ? View.VISIBLE : View.GONE);
        if (!on) mStates = Collections.emptyMap();
        mAdapter.notifyDataSetChanged();
        if (on) {
            expandBuiltIn();
        } else {
            // Test mode opened the built-in tools; leaving it closes them again.
            for (int i = 0; i < mApps.size(); i++) mList.collapseGroup(i);
        }
    }

    /** Opens the built-in tools, whose states test mode is there to show. */
    private void expandBuiltIn() {
        for (int i = 0; i < mApps.size(); i++) {
            if (mApps.get(i).get(0).builtIn) mList.expandGroup(i);
        }
    }

    /** Starts following the device for test mode; {@link #showStates} then reads it. */
    void startStates() {
        if (mFollowing) return;
        mFollowing = true;
        mDevice.start(new Handler(Looper.getMainLooper()));
    }

    void stopStates() {
        if (!mFollowing) return;
        mFollowing = false;
        mDevice.stop();
    }

    /** Re-reads the device and redraws the rows if anything changed. */
    void showStates() {
        if (!mTestMode) return;
        Map<String, String> states = mDevice.read();
        if (states.equals(mStates)) return;
        mStates = states;
        mAdapter.notifyDataSetChanged();
    }

    /**
     * Test mode: the sessions part of getStatus. Read-only: what each app's
     * session gave Jarvis and what Jarvis did, also after the session ended.
     */
    void showSessions(JSONObject sessions) {
        if (!mTestMode) return;
        String before = mSessions != null ? mSessions.toString() : "";
        mSessions = sessions;
        if (!before.equals(sessions != null ? sessions.toString() : "")) {
            mAdapter.notifyDataSetChanged();
        }
    }

    /** The declared session of an app, or null. */
    private JSONObject declared(String packageName) {
        JSONArray all = mSessions != null ? mSessions.optJSONArray("declared") : null;
        for (int i = 0; all != null && i < all.length(); i++) {
            JSONObject d = all.optJSONObject(i);
            if (d != null && packageName.equals(d.optString("app"))) return d;
        }
        return null;
    }

    /** The app's open session, else its last ended one, else null. */
    private JSONObject latest(String packageName) {
        if (mSessions == null) return null;
        for (String key : new String[] {"open", "last"}) {
            JSONObject s = mSessions.optJSONObject(key);
            if (s != null && packageName.equals(s.optString("app"))) return s;
        }
        return null;
    }

    /** The data of the session's newest event that carries a board or values. */
    private static JSONObject lastData(JSONObject session) {
        JSONArray events = session != null ? session.optJSONArray("events") : null;
        for (int i = events != null ? events.length() - 1 : -1; i >= 0; i--) {
            JSONObject data = events.optJSONObject(i).optJSONObject("data");
            if (data != null && data.length() > 0) return data;
        }
        return null;
    }

    /** Three lines under the app's name: the session, the last state, Jarvis's last turn. */
    private String sessionSummary(String packageName) {
        JSONObject session = latest(packageName);
        if (session == null) return mActivity.getString(R.string.session_none);
        StringBuilder text = new StringBuilder();
        long seconds = session.optLong("seconds");
        if (session.has("ended")) {
            text.append(mActivity.getString(R.string.session_last, session.optInt("turns"),
                    seconds / 60, seconds % 60, session.optString("ended")));
        } else {
            text.append(mActivity.getString(session.optBoolean("inTurn")
                    ? R.string.session_open_thinking : R.string.session_open,
                    session.optInt("turns")));
        }
        JSONObject data = lastData(session);
        if (data != null && data.has("board")) {
            text.append('\n').append(mActivity.getString(R.string.session_board,
                    data.optString("board"), data.optString("turn", "?")));
        }
        JSONArray turns = session.optJSONArray("turnTraces");
        if (turns != null && turns.length() > 0) {
            text.append('\n').append(mActivity.getString(R.string.session_last_turn,
                    describeTurn(turns.optJSONObject(turns.length() - 1))));
        }
        return text.toString();
    }

    /** "evaluate_moves → move(cell=4) · “Corners again?” · 17 s" from a turn trace. */
    private static String describeTurn(JSONObject trace) {
        StringBuilder text = new StringBuilder();
        String said = null;
        JSONArray steps = trace.optJSONArray("steps");
        for (int i = 0; steps != null && i < steps.length(); i++) {
            JSONObject step = steps.optJSONObject(i);
            JSONArray calls = step.optJSONArray("calls");
            for (int j = 0; calls != null && j < calls.length(); j++) {
                JSONObject call = calls.optJSONObject(j);
                if (text.length() > 0) text.append(" → ");
                text.append(call.optString("name"));
                JSONObject args = call.optJSONObject("args");
                if (args != null && args.length() > 0) {
                    StringBuilder list = new StringBuilder();
                    for (java.util.Iterator<String> k = args.keys(); k.hasNext(); ) {
                        String key = k.next();
                        if (list.length() > 0) list.append(", ");
                        list.append(key).append('=').append(args.opt(key));
                    }
                    text.append('(').append(list).append(')');
                }
            }
            if (step.has("said_text")) {
                said = step.optString("said_text").replaceFirst("^Said to the user: ", "");
            }
        }
        if (said != null) text.append(" · “").append(said).append('”');
        text.append(" · ").append(trace.optLong("ms") / 1000).append(" s");
        return text.toString();
    }

    /** Test mode, a tool of an app with a session: open or closed now, and its values. */
    private String sessionToolState(Tool tool) {
        JSONObject declared = declared(tool.packageName);
        if (declared == null) return null;
        if (tool.name.equals(declared.optString("startTool"))) {
            return mActivity.getString(R.string.session_tool_start);
        }
        JSONArray tools = declared.optJSONArray("tools");
        boolean sessionTool = false;
        for (int i = 0; tools != null && i < tools.length(); i++) {
            if (tool.name.equals(tools.optString(i))) sessionTool = true;
        }
        if (!sessionTool) return null;
        JSONObject open = mSessions.optJSONObject("open");
        String state;
        if (open == null || !tool.packageName.equals(open.optString("app"))) {
            state = mActivity.getString(R.string.session_tool_closed);
        } else {
            JSONArray openTools = open.optJSONArray("openTools");
            boolean on = false;
            for (int i = 0; openTools != null && i < openTools.length(); i++) {
                if (tool.name.equals(openTools.optString(i))) on = true;
            }
            state = mActivity.getString(on ? R.string.session_tool_open : R.string.session_tool_off);
        }
        // The value the turn tool takes, limited to what the game allows now.
        JSONObject turn = declared.optJSONObject("turn");
        if (turn != null && tool.name.equals(turn.optString("tool"))) {
            JSONObject data = lastData(latest(tool.packageName));
            JSONArray choices = data != null ? data.optJSONArray(turn.optString("choices")) : null;
            if (choices != null && choices.length() > 0) {
                StringBuilder values = new StringBuilder();
                for (int i = 0; i < choices.length(); i++) {
                    if (i > 0) values.append(' ');
                    values.append(choices.opt(i));
                }
                state += "\n" + turn.optString("arg") + ": " + values;
            }
        }
        return state;
    }

    /** Runs the RESET calls one after another through the service, as the Run button does. */
    private void reset() {
        mReset.setEnabled(false);
        mResetText.setText(R.string.tools_resetting);
        mActivity.call(jarvis -> {
            StringBuilder failures = new StringBuilder();
            for (String[] call : RESET) {
                String reply = jarvis.callTool(call[0], call[1]);
                if (reply == null || reply.startsWith("Error")) {
                    if (failures.length() > 0) failures.append("; ");
                    failures.append(call[0]).append(": ").append(reply);
                }
            }
            return failures.toString();
        }, (failures, error) -> {
            mReset.setEnabled(true);
            String problem = error != null ? error : failures;
            mResetText.setText(problem.isEmpty()
                    ? mActivity.getString(R.string.tools_reset_done,
                            android.text.format.DateFormat.getTimeFormat(mActivity)
                                    .format(new java.util.Date()))
                    : mActivity.getString(R.string.tools_reset_failed, problem));
            showStates();
        });
    }

    void load() {
        mActivity.call(JarvisManager::listTools, (json, error) -> {
            mApps.clear();
            if (error == null) {
                try {
                    String owner = null;
                    for (Tool tool : Tool.parseAll(json)) {
                        if (!tool.packageName.equals(owner)) {
                            owner = tool.packageName;
                            mApps.add(new ArrayList<>());
                        }
                        mApps.get(mApps.size() - 1).add(tool);
                    }
                } catch (JSONException e) {
                    Log.w(TAG, "Unreadable tool list", e);
                    error = String.valueOf(e.getMessage());
                }
            }
            mEmpty.setText(error != null ? error : mActivity.getString(R.string.tools_empty));
            mEmpty.setVisibility(mApps.isEmpty() ? View.VISIBLE : View.GONE);
            mAdapter.notifyDataSetChanged();
            if (mTestMode) expandBuiltIn();
        });
    }

    private String owner(Tool tool) {
        return tool.builtIn ? mActivity.getString(R.string.tools_builtin)
                : tool.appLabel + " · " + tool.packageName;
    }

    private void open(Tool tool) {
        View form = mActivity.getLayoutInflater().inflate(R.layout.dialog_tool, null);
        ((TextView) form.findViewById(R.id.owner)).setText(owner(tool));
        ((TextView) form.findViewById(R.id.description)).setText(tool.description);
        LinearLayout params = form.findViewById(R.id.params);
        TextView result = form.findViewById(R.id.result);

        // One input per declared parameter, in the same order as tool.params.
        List<View> inputs = new ArrayList<>();
        for (Tool.Param param : tool.params) {
            TextView label = new TextView(mActivity);
            label.setText(param.name + " · " + param.type + " · " + mActivity.getString(
                    param.required ? R.string.tool_required : R.string.tool_optional));
            label.setTextSize(12);
            label.setTextColor(mActivity.getColor(R.color.text_muted));
            label.setPadding(0, dp(12), 0, 0);
            params.addView(label);

            View input;
            if ("boolean".equals(param.type)) {
                CheckBox box = new CheckBox(mActivity);
                box.setText(param.description);
                input = box;
            } else {
                EditText edit = new EditText(mActivity);
                edit.setHint(param.description);
                edit.setTextSize(15);
                edit.setInputType(isNumber(param)
                        ? InputType.TYPE_CLASS_NUMBER | InputType.TYPE_NUMBER_FLAG_SIGNED
                                | ("integer".equals(param.type) ? 0
                                        : InputType.TYPE_NUMBER_FLAG_DECIMAL)
                        : InputType.TYPE_CLASS_TEXT);
                input = edit;
            }
            params.addView(input, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            inputs.add(input);
        }
        if (tool.params.isEmpty()) {
            TextView none = new TextView(mActivity);
            none.setText(R.string.tool_no_params);
            none.setTextColor(mActivity.getColor(R.color.text_muted));
            none.setPadding(0, dp(8), 0, 0);
            params.addView(none);
        }

        AlertDialog dialog = new AlertDialog.Builder(mActivity)
                .setTitle(tool.name)
                .setView(form)
                .setPositiveButton(R.string.tool_run, null)
                .setNegativeButton(R.string.tool_close, null)
                .create();
        // Shrink the form when the keyboard opens, so Run stays reachable.
        dialog.getWindow().setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
        dialog.show();

        // Set here rather than in the builder so that Run leaves the dialog open.
        Button run = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
        run.setOnClickListener(v -> {
            final String args = arguments(tool, inputs);
            if (args == null) return;
            run.setEnabled(false);
            result.setVisibility(View.VISIBLE);
            result.setTextColor(mActivity.getColor(R.color.text_muted));
            result.setText(R.string.tool_running);
            mActivity.call(jarvis -> jarvis.callTool(tool.name, args), (reply, error) -> {
                String text = error != null ? error : reply;
                // The service reports a failed call as a reply starting "Error:".
                boolean failed = error != null || text.startsWith("Error");
                result.setTextColor(mActivity.getColor(failed ? R.color.error : R.color.text));
                result.setText(text);
                run.setEnabled(true);
            });
        });
    }

    /**
     * The form's contents as a JSON object. Empty fields are left out, so a
     * missing required argument is reported by the tool itself. Returns null,
     * with the field marked, if a number cannot be read.
     */
    private String arguments(Tool tool, List<View> inputs) {
        JSONObject args = new JSONObject();
        try {
            for (int i = 0; i < inputs.size(); i++) {
                Tool.Param param = tool.params.get(i);
                if (inputs.get(i) instanceof CheckBox) {
                    args.put(param.name, ((CheckBox) inputs.get(i)).isChecked());
                    continue;
                }
                EditText edit = (EditText) inputs.get(i);
                String value = edit.getText().toString().trim();
                if (value.isEmpty()) continue;
                try {
                    if ("integer".equals(param.type)) args.put(param.name, Long.parseLong(value));
                    else if (isNumber(param)) args.put(param.name, Double.parseDouble(value));
                    else args.put(param.name, value);
                } catch (NumberFormatException e) {
                    edit.setError(mActivity.getString(R.string.tool_bad_number));
                    edit.requestFocus();
                    return null;
                }
            }
        } catch (JSONException e) {
            Log.w(TAG, "Could not build arguments for " + tool.name, e);
        }
        return args.toString();
    }

    private static boolean isNumber(Tool.Param param) {
        return "integer".equals(param.type) || "number".equals(param.type);
    }

    private int dp(int value) {
        return Math.round(value * mActivity.getResources().getDisplayMetrics().density);
    }

    private final BaseExpandableListAdapter mAdapter = new BaseExpandableListAdapter() {
        @Override public int getGroupCount() { return mApps.size(); }
        @Override public int getChildrenCount(int app) { return mApps.get(app).size(); }
        @Override public Object getGroup(int app) { return mApps.get(app); }
        @Override public Object getChild(int app, int tool) { return mApps.get(app).get(tool); }
        @Override public long getGroupId(int app) { return app; }
        @Override public long getChildId(int app, int tool) { return tool; }
        @Override public boolean hasStableIds() { return false; }
        @Override public boolean isChildSelectable(int app, int tool) { return true; }

        @Override
        public View getGroupView(int app, boolean expanded, View view, ViewGroup parent) {
            if (view == null) {
                view = mActivity.getLayoutInflater().inflate(R.layout.item_tool_app, parent, false);
            }
            List<Tool> tools = mApps.get(app);
            Tool first = tools.get(0);
            ((TextView) view.findViewById(R.id.title)).setText(first.appLabel);
            TextView subtitle = view.findViewById(R.id.subtitle);
            boolean session = mTestMode && !first.builtIn && declared(first.packageName) != null;
            subtitle.setSingleLine(!session);
            subtitle.setMaxLines(session ? 10 : 1);
            subtitle.setText(first.builtIn ? mActivity.getString(R.string.tools_builtin)
                    : session ? sessionSummary(first.packageName) : first.packageName);
            ((TextView) view.findViewById(R.id.count)).setText(mActivity.getResources()
                    .getQuantityString(R.plurals.tools_count, tools.size(), tools.size()));
            ImageView icon = view.findViewById(R.id.icon);
            try {
                icon.setImageDrawable(
                        mActivity.getPackageManager().getApplicationIcon(first.packageName));
            } catch (PackageManager.NameNotFoundException e) {
                // Built-in tools belong to no installed package.
                icon.setImageResource(R.mipmap.ic_launcher);
            }
            return view;
        }

        @Override
        public View getChildView(int app, int tool, boolean last, View view, ViewGroup parent) {
            if (view == null) {
                view = mActivity.getLayoutInflater().inflate(R.layout.item_tool, parent, false);
            }
            Tool t = mApps.get(app).get(tool);
            ((TextView) view.findViewById(R.id.title)).setText(t.name);
            ((TextView) view.findViewById(R.id.subtitle)).setText(t.description);
            TextView state = view.findViewById(R.id.state);
            String value = t.builtIn ? mStates.get(t.name)
                    : mTestMode && mSessions != null ? sessionToolState(t) : null;
            state.setText(value);
            state.setVisibility(value != null ? View.VISIBLE : View.GONE);
            return view;
        }
    };
}
