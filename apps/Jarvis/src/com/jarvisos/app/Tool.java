package com.jarvisos.app;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/** One entry of the tool registry, as IToolRegistry.listTools() describes it. */
final class Tool {

    static final class Param {
        String name;
        String type;
        String description;
        boolean required;
    }

    String name;
    String description;
    String appLabel;
    String packageName;
    /** True for tools JarvisOS runs itself rather than sending to an app. */
    boolean builtIn;
    final List<Param> params = new ArrayList<>();

    /** Parses the registry's JSON array. Tools arrive grouped by owning app. */
    static List<Tool> parseAll(String json) throws JSONException {
        List<Tool> tools = new ArrayList<>();
        JSONArray array = new JSONArray(json);
        for (int i = 0; i < array.length(); i++) {
            JSONObject o = array.getJSONObject(i);
            Tool tool = new Tool();
            tool.name = o.optString("toolName");
            tool.description = o.optString("description");
            tool.builtIn = o.optString("receiverClass").startsWith("@system/");
            JSONObject app = o.optJSONObject("app");
            tool.appLabel = app != null ? app.optString("appLabel") : "";
            tool.packageName = app != null ? app.optString("packageName") : "";

            // paramsJson is a JSON array encoded as a string; empty when a
            // tool declares nothing.
            String params = o.optString("paramsJson");
            if (!params.isEmpty()) {
                JSONArray list = new JSONArray(params);
                for (int j = 0; j < list.length(); j++) {
                    JSONObject p = list.getJSONObject(j);
                    Param param = new Param();
                    param.name = p.optString("name");
                    param.type = p.optString("type", "string");
                    param.description = p.optString("description");
                    param.required = p.optBoolean("required");
                    tool.params.add(param);
                }
            }
            tools.add(tool);
        }
        tools.sort((a, b) -> {
            if (a.builtIn != b.builtIn) return a.builtIn ? 1 : -1;   // apps first
            int byApp = a.packageName.compareTo(b.packageName);
            return byApp != 0 ? byApp : a.name.compareTo(b.name);
        });
        return tools;
    }
}
