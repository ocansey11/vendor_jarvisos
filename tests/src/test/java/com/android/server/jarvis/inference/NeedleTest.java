package com.android.server.jarvis.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Reading the Needle engine's replies. The strings are ones the engine produced on 8 Oct 2026. */
class NeedleTest {

    @Test
    void typedRequestGivesOneCall() throws Exception {
        Needle.Decision d = Needle.parse("{\"type\":\"call\",\"success\":true,\"error\":null,"
                + "\"function_calls\":[{\"name\":\"set_flashlight\",\"arguments\":{\"enabled\":true}}],"
                + "\"suppressed_calls\":[],\"reasoning\":\"'turn on the flashlight' -> enabled true.\","
                + "\"confidence\":0.9709,\"prefill_tps\":556.9,\"decode_tps\":272.2}");
        assertEquals(1, d.calls.size());
        assertEquals("set_flashlight", d.calls.get(0).name);
        assertTrue(d.calls.get(0).arguments.getBoolean("enabled"));
        assertEquals(0.9709, d.confidence, 1e-9);
        assertNull(d.transcript);
    }

    @Test
    void spokenRequestCarriesTheTranscript() throws Exception {
        Needle.Decision d = Needle.parse("{\"function_calls\":[{\"name\":\"set_flashlight\","
                + "\"arguments\":{\"enabled\":false}}],\"confidence\":0.9645,"
                + "\"audio_text\":\"turn off the flashlight.\",\"audio_language\":\"en\","
                + "\"audio_ttft_ms\":16.2,\"audio_decode_tps\":305.1}");
        assertEquals("turn off the flashlight.", d.transcript);
        assertEquals(16.2, d.speechFirstTokenMs, 1e-9);
        assertEquals(false, d.calls.get(0).arguments.getBoolean("enabled"));
    }

    @Test
    void twoRequestsGiveTwoCallsInOrder() throws Exception {
        Needle.Decision d = Needle.parse("{\"function_calls\":["
                + "{\"name\":\"toggle_wifi\",\"arguments\":{\"enabled\":false}},"
                + "{\"name\":\"set_flashlight\",\"arguments\":{\"enabled\":true}}],"
                + "\"confidence\":0.8621}");
        assertEquals(2, d.calls.size());
        assertEquals("toggle_wifi", d.calls.get(0).name);
        assertEquals("set_flashlight", d.calls.get(1).name);
    }

    @Test
    void noToolAndMissingArgumentsAreNotErrors() throws Exception {
        assertTrue(Needle.parse("{\"function_calls\":[],\"confidence\":0.1}").calls.isEmpty());
        assertTrue(Needle.parse("{\"confidence\":0.1}").calls.isEmpty());
        Needle.Decision d = Needle.parse("{\"function_calls\":[{\"name\":\"get_battery_status\"}]}");
        assertEquals(0, d.calls.get(0).arguments.length());
        assertEquals(0.0, d.confidence, 0);
    }
}
