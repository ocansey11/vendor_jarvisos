package com.android.server.jarvis.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.json.JSONArray;
import org.junit.jupiter.api.Test;

/**
 * What Cactus is handed as tools. Its parser only finds a tool by its
 * "function" key; until 22 Oct 2026 Jarvis sent bare definitions and Cactus
 * saw none (roadblock 50).
 */
class CactusEngineTest {

    @Test
    void bareDefinitionsAreWrapped() throws Exception {
        JSONArray tools = new JSONArray(CactusEngine.openAiTools(
                "[{\"name\":\"set_flashlight\",\"description\":\"Torch\","
                + "\"parameters\":{\"type\":\"object\"}}]"));
        assertEquals(1, tools.length());
        assertEquals("function", tools.getJSONObject(0).getString("type"));
        assertEquals("set_flashlight",
                tools.getJSONObject(0).getJSONObject("function").getString("name"));
    }

    @Test
    void wrappedDefinitionsPassThrough() throws Exception {
        String wrapped = "[{\"type\":\"function\",\"function\":{\"name\":\"set_alarm\"}}]";
        JSONArray tools = new JSONArray(CactusEngine.openAiTools(wrapped));
        assertEquals("set_alarm", tools.getJSONObject(0).getJSONObject("function").getString("name"));
        assertEquals(1, tools.length());
    }

    @Test
    void noToolsStaysNoTools() {
        assertNull(CactusEngine.openAiTools(null));
        assertEquals("", CactusEngine.openAiTools(""));
    }
}
