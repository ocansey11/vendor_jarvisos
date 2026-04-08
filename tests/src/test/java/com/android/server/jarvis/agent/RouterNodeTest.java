package com.android.server.jarvis.agent;

import com.android.server.jarvis.model.AgentSession;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * RouterNode is pure logic — no mocks needed.
 *
 * Every routing decision JarvisExecutor makes goes through here,
 * so this is the most important test class to keep green.
 */
class RouterNodeTest {

    private RouterNode router;
    private AgentSession session;

    @BeforeEach
    void setUp() {
        router  = new RouterNode();
        session = new AgentSession();
        session.maxTurns  = 5;
        session.turnCount = 0;
        session.status    = "PLANNING";
    }

    // -------------------------------------------------------------------------
    // Tool call detection
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Output with <|tool_call|> token routes to TOOL_CALL")
    void toolCallToken_routesToToolCall() {
        String output = "<|tool_call|>{\"name\":\"check_visa\",\"arguments\":{}}<|end_tool_call|>";
        assertEquals(RouterNode.Next.TOOL_CALL, router.route(session, output));
    }

    @Test
    @DisplayName("Output without tool call token does not route to TOOL_CALL")
    void noToolCallToken_doesNotRouteToToolCall() {
        String output = "I can help you with that.";
        assertNotEquals(RouterNode.Next.TOOL_CALL, router.route(session, output));
    }

    @Test
    @DisplayName("Tool call token embedded in prose is still detected")
    void toolCallTokenInProse_isDetected() {
        String output = "Sure! <|tool_call|>{\"name\":\"search\",\"arguments\":{\"q\":\"test\"}}<|end_tool_call|> done.";
        assertEquals(RouterNode.Next.TOOL_CALL, router.route(session, output));
    }

    // -------------------------------------------------------------------------
    // maxTurns failsafe
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Hitting maxTurns routes to FAILED regardless of output")
    void maxTurnsHit_routesToFailed() {
        session.turnCount = 5; // == maxTurns
        assertEquals(RouterNode.Next.FAILED, router.route(session, "anything"));
    }

    @Test
    @DisplayName("One turn below maxTurns does not fail")
    void oneBelowMaxTurns_doesNotFail() {
        session.turnCount = 4;
        assertNotEquals(RouterNode.Next.FAILED, router.route(session, "some output"));
    }

    // -------------------------------------------------------------------------
    // Status passthrough
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("DONE status routes to DONE immediately")
    void doneStatus_routesToDone() {
        session.status = "DONE";
        assertEquals(RouterNode.Next.DONE, router.route(session, null));
    }

    @Test
    @DisplayName("FAILED status routes to FAILED immediately")
    void failedStatus_routesToFailed() {
        session.status = "FAILED";
        // turnCount below limit — status check takes priority
        assertEquals(RouterNode.Next.FAILED, router.route(session, null));
    }

    // -------------------------------------------------------------------------
    // First-turn routing
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("First turn with no prior status routes to PLAN")
    void firstTurn_routesToPlan() {
        session.turnCount = 0;
        session.status    = "NEW"; // not PLANNING yet
        assertEquals(RouterNode.Next.PLAN, router.route(session, null));
    }

    // -------------------------------------------------------------------------
    // Retrieval signals
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Model saying it lacks info routes to RETRIEVE when no context yet")
    void noContext_lackInfoSignal_routesToRetrieve() {
        session.accumulatedContext = null;
        session.turnCount = 1;
        assertEquals(RouterNode.Next.RETRIEVE,
                router.route(session, "I don't have information about that."));
    }

    @Test
    @DisplayName("Retrieval signal ignored when context already accumulated")
    void withContext_lackInfoSignal_doesNotRetrieveAgain() {
        session.accumulatedContext = "some retrieved context";
        session.turnCount = 1;
        // Should not loop on retrieval — route to RESPOND instead
        assertEquals(RouterNode.Next.RESPOND,
                router.route(session, "I don't have information about that."));
    }

    // -------------------------------------------------------------------------
    // Default path
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Normal output with no signals routes to RESPOND")
    void normalOutput_routesToRespond() {
        session.turnCount = 1;
        assertEquals(RouterNode.Next.RESPOND,
                router.route(session, "The answer to your question is 42."));
    }

    @Test
    @DisplayName("Null output routes to RESPOND (not crash)")
    void nullOutput_routesToRespond() {
        session.turnCount = 1;
        assertEquals(RouterNode.Next.RESPOND, router.route(session, null));
    }
}
