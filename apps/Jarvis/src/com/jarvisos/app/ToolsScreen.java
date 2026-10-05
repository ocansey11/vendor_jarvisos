package com.jarvisos.app;

import android.app.AlertDialog;
import android.jarvis.JarvisManager;
import android.text.InputType;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * The Tools tab: every tool in the registry under the app that owns it. Tapping
 * one opens a form built from the tool's declared parameters; Run calls the
 * tool through the service, with no model involved, and shows its reply.
 */
class ToolsScreen {

    private static final String TAG = "JarvisApp";

    private final MainActivity mActivity;
    /** Tools, with a null before each group to stand for its header row. */
    private final List<Tool> mRows = new ArrayList<>();
    private final TextView mEmpty;

    ToolsScreen(MainActivity activity) {
        mActivity = activity;
        mEmpty = activity.findViewById(R.id.tools_empty);
        ListView list = activity.findViewById(R.id.tools_list);
        list.setAdapter(mAdapter);
        list.setOnItemClickListener((parent, view, position, id) -> open(mRows.get(position)));
    }

    void load() {
        mActivity.call(JarvisManager::listTools, (json, error) -> {
            mRows.clear();
            if (error == null) {
                try {
                    String group = null;
                    for (Tool tool : Tool.parseAll(json)) {
                        if (!tool.packageName.equals(group)) {
                            group = tool.packageName;
                            mRows.add(null);
                        }
                        mRows.add(tool);
                    }
                } catch (JSONException e) {
                    Log.w(TAG, "Unreadable tool list", e);
                    error = String.valueOf(e.getMessage());
                }
            }
            mEmpty.setText(error != null ? error : mActivity.getString(R.string.tools_empty));
            mEmpty.setVisibility(mRows.isEmpty() ? View.VISIBLE : View.GONE);
            mAdapter.notifyDataSetChanged();
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

    private final BaseAdapter mAdapter = new BaseAdapter() {
        @Override public int getCount() { return mRows.size(); }
        @Override public Object getItem(int position) { return mRows.get(position); }
        @Override public long getItemId(int position) { return position; }
        @Override public int getViewTypeCount() { return 2; }
        @Override public int getItemViewType(int position) { return isEnabled(position) ? 1 : 0; }
        @Override public boolean areAllItemsEnabled() { return false; }
        @Override public boolean isEnabled(int position) { return mRows.get(position) != null; }

        @Override
        public View getView(int position, View view, ViewGroup parent) {
            Tool tool = mRows.get(position);
            if (view == null) {
                view = mActivity.getLayoutInflater().inflate(tool != null
                        ? R.layout.item_tool : R.layout.item_tool_header, parent, false);
            }
            TextView title = view.findViewById(R.id.title);
            TextView subtitle = view.findViewById(R.id.subtitle);
            if (tool != null) {
                title.setText(tool.name);
                subtitle.setText(tool.description);
            } else {
                Tool first = mRows.get(position + 1);
                title.setText(first.builtIn
                        ? mActivity.getString(R.string.tools_builtin) : first.appLabel);
                subtitle.setText(first.packageName);
            }
            return view;
        }
    };
}
