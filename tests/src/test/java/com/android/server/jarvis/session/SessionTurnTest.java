package com.android.server.jarvis.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.android.server.jarvis.inference.Completion;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kxml2.io.KXmlParser;

import java.io.StringReader;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;

/**
 * A session turn with a scripted model and a fake game: what the model is
 * offered, how wrong calls are retried, and when Jarvis gives up.
 */
class SessionTurnTest {

    private static final String TOOLS = "[{\"name\":\"get_state\",\"description\":\"d\","
            + "\"parameters\":{\"type\":\"object\",\"properties\":{},\"required\":[]}},"
            + "{\"name\":\"move\",\"description\":\"d\",\"parameters\":{\"type\":\"object\","
            + "\"properties\":{\"cell\":{\"type\":\"integer\",\"description\":\"\"}},"
            + "\"required\":[\"cell\"]}},"
            + "{\"name\":\"say\",\"description\":\"d\",\"parameters\":{\"type\":\"object\","
            + "\"properties\":{\"text\":{\"type\":\"string\"}},\"required\":[\"text\"]}}]";

    private SessionManifest mManifest;
    /** Replies the "model" gives, in order. */
    private final Deque<String> mReplies = new ArrayDeque<>();
    /** What the model was offered on each call: tools and options. */
    private final List<String[]> mOffered = new ArrayList<>();
    /** Tool calls that reached the "dispatcher". */
    private final List<String> mCalls = new ArrayList<>();
    private final List<String> mSaid = new ArrayList<>();

    @BeforeEach
    void setUp() throws Exception {
        KXmlParser parser = new KXmlParser();
        parser.setInput(new StringReader("<session name=\"ttt\" title=\"Tic-tac-toe\">"
                + "<rules>Cells 0-8.</rules><tools>get_state, move</tools>"
                + "<events>player_moved, your_turn</events>"
                + "<turn event=\"your_turn\" tool=\"move\" arg=\"cell\" choices=\"legal\"/></session>"));
        mManifest = SessionManifest.parse("com.example", parser);
    }

    private static String calls(String... calls) {
        return "{\"success\":true,\"response\":\"\",\"function_calls\":[" + String.join(",", calls) + "]}";
    }

    private static String call(String name, String args) {
        return "{\"name\":\"" + name + "\",\"arguments\":" + args + "}";
    }

    /** A fake game: cell 4 is taken, everything else on the legal list plays. */
    private SessionTurn.Result run() {
        SessionTurn turn = new SessionTurn(
                (messages, tools, options) -> {
                    mOffered.add(new String[] {tools, options, messages});
                    return mReplies.isEmpty() ? null : Completion.parse(mReplies.removeFirst());
                },
                (name, args) -> {
                    mCalls.add(name + " " + args);
                    switch (name) {
                        case "say":
                            mSaid.add(args.optString("text"));
                            return "Said to the user: " + args.optString("text");
                        case "get_state": return "{\"board\":\"X...X....\"}";
                        case "move":
                            return args.optInt("cell") == 4 ? "Error: cell 4 is taken"
                                    : "You played cell " + args.optInt("cell");
                        default: return "Error: refused. '" + name + "' is closed";
                    }
                }, true);
        return turn.run(mManifest, null, List.of("get_state", "move"),
                new SessionPrompt.Event("your_turn", "{\"board\":\"X...X....\",\"legal\":[1,2,3,5]}"),
                List.of(new SessionPrompt.Event("player_moved", "{\"cell\":0}")),
                TOOLS, () -> true);
    }

    @Test
    @DisplayName("say then move in one reply: one model call, the line is said, the turn is done")
    void sayAndMove() {
        mReplies.add(calls(call("say", "{\"text\":\"Corner? Bold.\"}"), call("move", "{\"cell\":2}")));
        SessionTurn.Result result = run();
        assertTrue(result.done, result.detail);
        assertEquals(List.of("Corner? Bold."), mSaid);
        assertEquals(1, mOffered.size());
        // The move's cell is narrowed to the event's legal cells, under a grammar.
        assertTrue(mOffered.get(0)[0].contains("\"enum\":[1,2,3,5]"), mOffered.get(0)[0]);
        JSONObject options = new JSONObject(mOffered.get(0)[1]);
        assertTrue(options.getBoolean("constrain_tools"));
        assertEquals("required", options.getString("tool_choice"));
    }

    @Test
    @DisplayName("A helper tool first, then the move: two model calls, the result goes back in")
    void helperThenMove() {
        mReplies.add(calls(call("get_state", "{}")));
        mReplies.add(calls(call("move", "{\"cell\":5}")));
        SessionTurn.Result result = run();
        assertTrue(result.done, result.detail);
        assertEquals(3, mOffered.size(), "two calls for the turn, one for a line after it");
        assertTrue(mOffered.get(1)[2].contains("X...X...."), "the tool result is in the second prompt");
    }

    @Test
    @DisplayName("A silent move is followed by one short call that may only say something")
    void lineAfterSilentMove() {
        mReplies.add(calls(call("move", "{\"cell\":2}")));
        mReplies.add(calls(call("say", "{\"text\":\"Corner taken.\"}")));
        SessionTurn.Result result = run();
        assertTrue(result.done, result.detail);
        assertEquals(List.of("Corner taken."), mSaid);
        String[] last = mOffered.get(1);
        assertTrue(last[0].contains("\"say\"") && !last[0].contains("\"move\""), "only say is offered");
        assertTrue(last[2].contains("about your move"));
    }

    @Test
    @DisplayName("An illegal move is retried with only the move tool, forced")
    void illegalMoveRetried() {
        mReplies.add(calls(call("move", "{\"cell\":4}")));
        mReplies.add(calls(call("move", "{\"cell\":1}")));
        SessionTurn.Result result = run();
        assertTrue(result.done, result.detail);
        String[] retry = mOffered.get(1);
        assertFalse(retry[0].contains("get_state"), "only the turn tool is offered on a retry");
        assertEquals("required", new JSONObject(retry[1]).getString("tool_choice"));
        assertTrue(retry[2].contains("set to one of [1,2,3,5]"), "the model is told the legal cells");
    }

    @Test
    @DisplayName("A refused tool counts as wrong; after too many wrong calls Jarvis gives up")
    void givesUp() {
        mReplies.add(calls(call("set_alarm", "{}")));
        mReplies.add(calls(call("move", "{\"cell\":4}")));
        mReplies.add(calls(call("move", "{\"cell\":4}")));
        mReplies.add(calls(call("move", "{\"cell\":1}")));
        SessionTurn.Result result = run();
        assertFalse(result.done);
        assertTrue(result.detail.startsWith("gave up after 3 wrong calls"), result.detail);
        assertEquals(3, mOffered.size(), "no fourth model call after the third wrong one");
    }

    @Test
    @DisplayName("A reply with no call is a wrong one, its words become the say; one say per turn")
    void noCallAndOneSay() {
        mReplies.add("{\"success\":true,\"response\":\"Hmm, let me think.\",\"function_calls\":[]}");
        mReplies.add(calls(call("say", "{\"text\":\"one\"}"), call("say", "{\"text\":\"two\"}"),
                call("move", "{\"cell\":3}")));
        SessionTurn.Result result = run();
        assertTrue(result.done, result.detail);
        assertEquals(List.of("Hmm, let me think."), mSaid);
    }

    @Test
    @DisplayName("An engine failure ends the turn at once, not done")
    void engineFails() {
        SessionTurn.Result result = run();
        assertFalse(result.done);
        assertEquals("the model failed", result.detail);
    }

    @Test
    @DisplayName("The prompt has rules, honest empty memory, the open tools and the events; never prompt text from the app")
    void prompt() throws Exception {
        JSONArray messages = SessionPrompt.messages(mManifest, null, List.of("get_state", "move"),
                new SessionPrompt.Event("your_turn", "{\"legal\":[1]}"),
                List.of(new SessionPrompt.Event("player_moved", "{\"cell\":0}")));
        String system = messages.getJSONObject(0).getString("content");
        String user = messages.getJSONObject(1).getString("content");
        assertTrue(system.contains("Rules: Cells 0-8."));
        assertTrue(system.contains(SessionPrompt.NO_FACTS));
        assertTrue(system.contains("Tools that work now: say, get_state, move."));
        assertTrue(user.contains("- player_moved {\"cell\":0}"));
        assertTrue(user.endsWith("It is your turn."));
        // Facts from Jarvis's memory go in when there are some.
        assertTrue(SessionPrompt.system(mManifest, "Known facts about this user:\n- Likes corners",
                List.of("move")).contains("Likes corners"));
    }

    @Test
    @DisplayName("The prompt stays within its budget by dropping the oldest events")
    void promptBudget() throws Exception {
        List<SessionPrompt.Event> many = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            many.add(new SessionPrompt.Event("player_moved", "{\"pad\":\"" + "x".repeat(400) + i + "\"}"));
        }
        JSONArray messages = SessionPrompt.messages(mManifest, null, List.of("move"),
                new SessionPrompt.Event("your_turn", "{\"legal\":[1]}"), many);
        int chars = messages.getJSONObject(0).getString("content").length()
                + messages.getJSONObject(1).getString("content").length();
        assertTrue(chars <= SessionPrompt.MAX_PROMPT_CHARS, "prompt is " + chars + " chars");
        String user = messages.getJSONObject(1).getString("content");
        assertTrue(user.contains("…"), "recent events are shortened");
        assertFalse(user.contains("x0\""), "the oldest events are dropped first");
    }
}
