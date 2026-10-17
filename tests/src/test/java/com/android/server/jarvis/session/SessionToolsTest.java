package com.android.server.jarvis.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.json.JSONObject;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** The tool fence of a session, and the limits on what an app sends. */
class SessionToolsTest {

    private static final Set<String> OPEN = new LinkedHashSet<>(List.of("get_state", "move"));

    @Test
    @DisplayName("During a session, a tool outside it is refused")
    void otherToolRefusedDuringSession() {
        String refusal = SessionTools.refusal("Tic-tac-toe", OPEN, "set_alarm", false);
        assertTrue(refusal.startsWith("Error: refused"), refusal);
        assertTrue(refusal.contains("say, get_state, move"), refusal);
    }

    @Test
    @DisplayName("During a session, its open tools and say run")
    void openToolsRun() {
        assertNull(SessionTools.refusal("Tic-tac-toe", OPEN, "move", true));
        assertNull(SessionTools.refusal("Tic-tac-toe", OPEN, "say", false));
    }

    @Test
    @DisplayName("A tool the app closed for this session is refused like any other")
    void closedToolRefused() {
        assertTrue(SessionTools.refusal("Tic-tac-toe", OPEN, "evaluate_moves", true)
                .startsWith("Error: refused"));
    }

    @Test
    @DisplayName("Outside a session, session tools are refused and other tools run")
    void outsideSession() {
        assertTrue(SessionTools.refusal(null, null, "move", true).startsWith("Error: refused"));
        assertNull(SessionTools.refusal(null, null, "set_alarm", false));
    }

    @Test
    @DisplayName("say keeps a speech bubble short and refuses empty text")
    void sayText() throws Exception {
        assertNull(SessionTools.sayText(new JSONObject().put("text", "   ")));
        assertEquals("Hi", SessionTools.sayText(new JSONObject().put("text", " Hi ")));
        String said = SessionTools.sayText(new JSONObject().put("text", "a".repeat(500)));
        assertEquals(SessionTools.MAX_SAY_CHARS, said.length());
    }

    @Test
    @DisplayName("say never cuts an emoji in half, and drops leaked think tags")
    void sayCutsWholeCharacters() throws Exception {
        String flames = "Hot! " + "\uD83D\uDD25".repeat(300);   // two chars each
        String said = SessionTools.sayText(new JSONObject().put("text", flames));
        assertEquals(SessionTools.MAX_SAY_CHARS, said.codePointCount(0, said.length()));
        assertTrue(said.endsWith("\uD83D\uDD25…"), "ends on a whole emoji");
        assertEquals("Your move.", SessionTools.sayText(new JSONObject().put("text", "Your move.</think>")));
    }

    @Test
    @DisplayName("Events: a burst passes, a flood is refused, then allowance comes back")
    void rateLimit() {
        EventLimiter limiter = new EventLimiter();
        for (int i = 0; i < EventLimiter.BURST; i++) assertNull(limiter.admit("{}", 1000));
        assertTrue(limiter.admit("{}", 1000).startsWith("too many events"));
        assertNull(limiter.admit("{}", 1600));   // half a second later: one more
    }

    @Test
    @DisplayName("Events: data over the size limit is refused")
    void sizeLimit() {
        EventLimiter limiter = new EventLimiter();
        String big = "{\"x\":\"" + "a".repeat(EventLimiter.MAX_DATA_CHARS) + "\"}";
        assertTrue(limiter.admit(big, 0).contains("limit"));
    }
}
