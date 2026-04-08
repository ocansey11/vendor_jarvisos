package com.android.server.jarvis.agent;

import com.android.server.jarvis.model.AgentSession;
import com.android.server.jarvis.model.AgentTurn;
import com.android.server.jarvis.tools.ToolDispatcher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests ToolNode's Gemma 4 token parsing and dispatch wiring.
 *
 * ToolDispatcher is mocked — these tests verify the parsing layer,
 * not the broadcast/ResultReceiver plumbing.
 */
@ExtendWith(MockitoExtension.class)
class ToolNodeParsingTest {

    @Mock
    private ToolDispatcher mockDispatcher;

    private ToolNode toolNode;
    private AgentSession session;

    @BeforeEach
    void setUp() {
        toolNode = new ToolNode(mockDispatcher);
        session  = new AgentSession();
        session.turns = new io.objectbox.relation.ToMany<>(session,
                com.android.server.jarvis.model.AgentSession_.turns);
    }

    // -------------------------------------------------------------------------
    // Happy path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Clean Gemma 4 tool call JSON is parsed and dispatched")
    void cleanToolCall_parsedAndDispatched() {
        when(mockDispatcher.dispatchByName("check_visa_status", "{\"passport_number\":\"GH123456\"}"))
                .thenReturn("Visa valid until 2027-01-01");

        String output =
                "<|tool_call|>\n" +
                "{\"name\":\"check_visa_status\",\"arguments\":{\"passport_number\":\"GH123456\"}}\n" +
                "<|end_tool_call|>";

        String result = toolNode.execute(session, output);

        assertEquals("Visa valid until 2027-01-01", result);
        verify(mockDispatcher).dispatchByName("check_visa_status",
                "{\"passport_number\":\"GH123456\"}");
    }

    @Test
    @DisplayName("Tool result is appended to accumulatedContext")
    void toolResult_appendedToContext() {
        when(mockDispatcher.dispatchByName(anyString(), anyString())).thenReturn("tool result");

        String output = "<|tool_call|>{\"name\":\"my_tool\",\"arguments\":{}}<|end_tool_call|>";
        toolNode.execute(session, output);

        assertNotNull(session.accumulatedContext);
        assertTrue(session.accumulatedContext.contains("tool result"));
        assertTrue(session.accumulatedContext.contains("my_tool"));
    }

    @Test
    @DisplayName("lastToolResult is set on session after dispatch")
    void lastToolResult_setOnSession() {
        when(mockDispatcher.dispatchByName(anyString(), anyString())).thenReturn("42");

        String output = "<|tool_call|>{\"name\":\"calc\",\"arguments\":{\"x\":\"1\"}}<|end_tool_call|>";
        toolNode.execute(session, output);

        assertEquals("42", session.lastToolResult);
    }

    // -------------------------------------------------------------------------
    // Missing close token (model cut off)
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Missing close token still parses JSON via brace matching")
    void missingCloseToken_fallbackBraceMatching() {
        when(mockDispatcher.dispatchByName(anyString(), anyString())).thenReturn("ok");

        // No <|end_tool_call|> — model was cut off
        String output = "<|tool_call|>{\"name\":\"search\",\"arguments\":{\"q\":\"Ghana\"}}";
        String result = toolNode.execute(session, output);

        assertNotEquals("Error: could not parse tool call from model output", result);
        verify(mockDispatcher).dispatchByName("search", "{\"q\":\"Ghana\"}");
    }

    // -------------------------------------------------------------------------
    // Error handling
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("No tool call token returns error string, does not throw")
    void noToken_returnsErrorString() {
        String result = toolNode.execute(session, "This is just plain text, no tokens.");
        assertTrue(result.startsWith("Error:"));
    }

    @Test
    @DisplayName("Malformed JSON between tokens returns error string, does not throw")
    void malformedJson_returnsErrorString() {
        String output = "<|tool_call|>NOT VALID JSON<|end_tool_call|>";
        String result = toolNode.execute(session, output);
        assertTrue(result.startsWith("Error:"));
    }

    @Test
    @DisplayName("Tool call with no arguments field still dispatches with empty args")
    void noArgumentsField_dispatchesEmptyArgs() {
        when(mockDispatcher.dispatchByName(anyString(), anyString())).thenReturn("done");

        String output = "<|tool_call|>{\"name\":\"ping\"}<|end_tool_call|>";
        String result = toolNode.execute(session, output);

        assertNotEquals("Error: could not parse tool call from model output", result);
        verify(mockDispatcher).dispatchByName("ping", "{}");
    }

    @Test
    @DisplayName("Status is set to TOOL_CALL on session")
    void statusSetToToolCall() {
        when(mockDispatcher.dispatchByName(anyString(), anyString())).thenReturn("x");

        toolNode.execute(session,
                "<|tool_call|>{\"name\":\"t\",\"arguments\":{}}<|end_tool_call|>");

        assertEquals("TOOL_CALL", session.status);
    }
}
