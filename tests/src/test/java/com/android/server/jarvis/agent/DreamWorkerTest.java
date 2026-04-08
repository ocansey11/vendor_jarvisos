package com.android.server.jarvis.agent;

import com.android.server.jarvis.model.AgentSession;
import com.android.server.jarvis.model.AgentTurn;
import com.android.server.jarvis.model.UserContext;
import org.json.JSONArray;
import org.json.JSONException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests DreamWorker's pure logic: session summarisation and fact merging.
 *
 * Uses reflection to access package-private methods — avoids modifying
 * production code just for tests. JarvisStore and CactusWrapper are not
 * involved here (tested in integration tests once hardware is available).
 */
class DreamWorkerTest {

    private DreamWorker dreamWorker;

    @BeforeEach
    void setUp() {
        // DreamWorker constructor needs Context + WorkerParameters (stubs)
        dreamWorker = new DreamWorker(null, null);
    }

    // -------------------------------------------------------------------------
    // buildSessionSummary
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("Session with no turns returns query-only summary")
    void noTurns_returnsQuerySummary() throws Exception {
        AgentSession session = new AgentSession();
        session.originalQuery = "What did I work on yesterday?";
        session.turns = new io.objectbox.relation.ToMany<>(session,
                com.android.server.jarvis.model.AgentSession_.turns);

        String summary = invokeBuildSessionSummary(session);

        assertNotNull(summary);
        assertTrue(summary.contains("What did I work on yesterday?"));
    }

    @Test
    @DisplayName("Tool turns appear in session summary")
    void toolTurns_appearInSummary() throws Exception {
        AgentSession session = buildSessionWithTurns(
                "check visa status",
                "tool", "check_visa_status", "Visa valid",
                "model", null, "Here is your visa status"
        );

        String summary = invokeBuildSessionSummary(session);

        assertNotNull(summary);
        assertTrue(summary.contains("check_visa_status"));
        assertTrue(summary.contains("Visa valid"));
    }

    @Test
    @DisplayName("Final answer appears in summary when present")
    void finalAnswer_appearsInSummary() throws Exception {
        AgentSession session = new AgentSession();
        session.originalQuery = "Hello";
        session.finalAnswer   = "Hi there!";
        session.turns = new io.objectbox.relation.ToMany<>(session,
                com.android.server.jarvis.model.AgentSession_.turns);

        String summary = invokeBuildSessionSummary(session);

        assertTrue(summary.contains("Hi there!"));
    }

    // -------------------------------------------------------------------------
    // mergeFacts
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("New facts are appended to empty UserContext")
    void emptyContext_newFactsAppended() throws Exception {
        UserContext ctx = new UserContext();
        ctx.facts = null;

        invokeMergeFacts(ctx, "[\"User is a software engineer\",\"User prefers concise answers\"]");

        assertNotNull(ctx.facts);
        JSONArray result = new JSONArray(ctx.facts);
        assertEquals(2, result.length());
        assertEquals("User is a software engineer", result.getString(0));
    }

    @Test
    @DisplayName("New facts are appended to existing facts")
    void existingFacts_newFactsAppended() throws Exception {
        UserContext ctx = new UserContext();
        ctx.facts = "[\"User works with Python\"]";

        invokeMergeFacts(ctx, "[\"User prefers dark mode\"]");

        JSONArray result = new JSONArray(ctx.facts);
        assertEquals(2, result.length());
        assertTrue(ctx.facts.contains("User works with Python"));
        assertTrue(ctx.facts.contains("User prefers dark mode"));
    }

    @Test
    @DisplayName("Empty array from model changes nothing")
    void emptyArrayFromModel_noChange() throws Exception {
        UserContext ctx = new UserContext();
        ctx.facts = "[\"existing fact\"]";

        invokeMergeFacts(ctx, "[]");

        JSONArray result = new JSONArray(ctx.facts);
        assertEquals(1, result.length());
    }

    @Test
    @DisplayName("Facts capped at 50 — oldest dropped")
    void factsCappedAt50() throws Exception {
        UserContext ctx = new UserContext();
        JSONArray existing = new JSONArray();
        for (int i = 0; i < 49; i++) existing.put("fact " + i);
        ctx.facts = existing.toString();

        // Adding 5 more — should cap at 50 (keep most recent)
        invokeMergeFacts(ctx, "[\"new1\",\"new2\",\"new3\",\"new4\",\"new5\"]");

        JSONArray result = new JSONArray(ctx.facts);
        assertEquals(50, result.length());
        // Most recent facts should be at the end
        assertTrue(ctx.facts.contains("new5"));
    }

    @Test
    @DisplayName("Model response with prose around JSON array still parses")
    void proseAroundJsonArray_stillParses() throws Exception {
        UserContext ctx = new UserContext();
        ctx.facts = null;

        // Model sometimes wraps its output in explanation text
        invokeMergeFacts(ctx,
                "Based on this session, I learned: [\"User is building an OS\"] Hope that helps!");

        assertNotNull(ctx.facts);
        JSONArray result = new JSONArray(ctx.facts);
        assertEquals(1, result.length());
        assertEquals("User is building an OS", result.getString(0));
    }

    @Test
    @DisplayName("Completely malformed model response does not throw or corrupt facts")
    void malformedResponse_doesNotCorrupt() throws Exception {
        UserContext ctx = new UserContext();
        ctx.facts = "[\"safe fact\"]";

        // No JSON array at all — should no-op
        invokeMergeFacts(ctx, "I could not determine any new facts from this session.");

        assertNotNull(ctx.facts);
        assertTrue(ctx.facts.contains("safe fact"));
    }

    // -------------------------------------------------------------------------
    // Reflection helpers — access private methods without changing production code
    // -------------------------------------------------------------------------

    private String invokeBuildSessionSummary(AgentSession session) throws Exception {
        Method m = DreamWorker.class.getDeclaredMethod("buildSessionSummary", AgentSession.class);
        m.setAccessible(true);
        return (String) m.invoke(dreamWorker, session);
    }

    private void invokeMergeFacts(UserContext ctx, String modelResponse) throws Exception {
        Method m = DreamWorker.class.getDeclaredMethod(
                "mergeFacts", UserContext.class, String.class);
        m.setAccessible(true);
        m.invoke(dreamWorker, ctx, modelResponse);
    }

    private AgentSession buildSessionWithTurns(String query, Object... turnData) {
        AgentSession session = new AgentSession();
        session.originalQuery = query;
        session.turns = new io.objectbox.relation.ToMany<>(session,
                com.android.server.jarvis.model.AgentSession_.turns);

        // turnData: role, toolName (or null), content — in triples
        for (int i = 0; i < turnData.length; i += 3) {
            AgentTurn turn = new AgentTurn();
            turn.role     = (String) turnData[i];
            turn.toolName = (String) turnData[i + 1];
            turn.content  = (String) turnData[i + 2];
            session.turns.add(turn);
        }
        return session;
    }
}
