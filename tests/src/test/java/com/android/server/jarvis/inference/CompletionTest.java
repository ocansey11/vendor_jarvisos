package com.android.server.jarvis.inference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Completion.parse: the one place an engine's reply envelope is taken apart. */
class CompletionTest {

    @Test
    @DisplayName("A text reply gives the text and no tool call")
    void textReply() {
        Completion c = Completion.parse(
                "{\"success\":true,\"error\":null,\"response\":\"Hi there!\",\"function_calls\":[]}");
        assertEquals("Hi there!", c.text);
        assertFalse(c.hasToolCall());
        assertNull(c.error);
    }

    @Test
    @DisplayName("A tool call gives its name and arguments")
    void toolCall() {
        Completion c = Completion.parse("{\"success\":true,\"response\":\"\",\"function_calls\":"
                + "[{\"name\":\"demo_set_alarm\",\"arguments\":{\"hour\":7,\"minute\":30}}]}");
        assertTrue(c.hasToolCall());
        assertEquals("demo_set_alarm", c.toolName);
        assertEquals(7, c.toolArguments.optInt("hour"));
        assertEquals(30, c.toolArguments.optInt("minute"));
        assertEquals("", c.text);
    }

    @Test
    @DisplayName("A tool call with no arguments still has an (empty) argument object")
    void toolCallWithoutArguments() {
        Completion c = Completion.parse(
                "{\"success\":true,\"response\":\"\",\"function_calls\":[{\"name\":\"get_battery_status\"}]}");
        assertEquals("get_battery_status", c.toolName);
        assertNotNull(c.toolArguments);
        assertEquals(0, c.toolArguments.length());
    }

    @Test
    @DisplayName("Only the first of several tool calls is taken")
    void firstToolCallOnly() {
        Completion c = Completion.parse("{\"response\":\"\",\"function_calls\":"
                + "[{\"name\":\"a\",\"arguments\":{}},{\"name\":\"b\",\"arguments\":{}}]}");
        assertEquals("a", c.toolName);
    }

    @Test
    @DisplayName("An engine failure carries the error")
    void engineError() {
        Completion c = Completion.parse(
                "{\"success\":false,\"error\":\"model not initialized\",\"response\":\"\",\"function_calls\":[]}");
        assertEquals("model not initialized", c.error);
        assertFalse(c.hasToolCall());
    }

    @Test
    @DisplayName("The old {\"error\":...} reply from the Cactus bridge is an error too")
    void bareErrorObject() {
        Completion c = Completion.parse("{\"error\":\"Model not initialized\"}");
        assertEquals("Model not initialized", c.error);
    }

    @Test
    @DisplayName("success:false with no message is still an error")
    void failureWithoutMessage() {
        Completion c = Completion.parse("{\"success\":false,\"response\":\"\"}");
        assertNotNull(c.error);
    }

    @Test
    @DisplayName("Plain text that is not JSON is the reply itself")
    void plainText() {
        Completion c = Completion.parse("The alarm is set.");
        assertEquals("The alarm is set.", c.text);
        assertNull(c.error);
    }

    @Test
    @DisplayName("JSON that is not an envelope is the reply itself, not unwrapped")
    void jsonThatIsNotAnEnvelope() {
        Completion c = Completion.parse("{\"facts\":[\"likes tea\"]}");
        assertEquals("{\"facts\":[\"likes tea\"]}", c.text);
    }

    @Test
    @DisplayName("No reply at all is null")
    void nullReply() {
        assertNull(Completion.parse(null));
    }

    @Test
    @DisplayName("Every call of a reply is kept in order, with grammar and timing details")
    void severalCalls() {
        Completion c = Completion.parse("{\"success\":true,\"response\":\"\",\"constrained\":true,"
                + "\"prefill_tokens\":812,\"total_time_ms\":10500.5,\"function_calls\":["
                + "{\"name\":\"say\",\"arguments\":{\"text\":\"Watch this.\"}},"
                + "{\"name\":\"move\",\"arguments\":{\"cell\":4}}]}");
        assertEquals("say", c.toolName);
        assertEquals(2, c.calls.length());
        assertEquals("move", c.calls.getJSONObject(1).getString("name"));
        assertEquals(4, c.calls.getJSONObject(1).getJSONObject("arguments").getInt("cell"));
        assertTrue(c.constrained);
        assertEquals(812, c.promptTokens);
        assertEquals(10500, c.engineMs);
    }
}
