package com.android.server.jarvis.session;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.kxml2.io.KXmlParser;
import org.xmlpull.v1.XmlPullParser;

import java.io.StringReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** SessionManifest.parse: what an app declares about its session, and what is refused. */
class SessionManifestTest {

    private static SessionManifest parse(String xml) throws Exception {
        XmlPullParser parser = new KXmlParser();
        parser.setInput(new StringReader(xml));
        return SessionManifest.parse("com.example", parser);
    }

    private static String session(String attributes, String body) {
        return "<session name=\"chess\" title=\"Chess\"" + attributes + ">"
                + "<rules>Normal chess.</rules>" + body + "</session>";
    }

    @Test
    @DisplayName("The sample game's own manifest parses, with its turn and start tool")
    void sampleGame() throws Exception {
        String xml = Files.readString(Path.of(
                "../apps/JarvisTicTacToe/res/xml/jarvis_session.xml"));
        SessionManifest m = parse(xml);
        assertEquals("tic_tac_toe", m.name);
        assertEquals("Tic-tac-toe", m.title);
        assertEquals("start_tic_tac_toe", m.startTool);
        assertEquals(List.of("get_state", "evaluate_moves", "move"), m.tools);
        assertEquals(List.of("player_moved", "your_turn", "game_over"), m.events);
        assertEquals("your_turn", m.turnEvent);
        assertEquals("move", m.turnTool);
        assertEquals("cell", m.turnArg);
        assertEquals("legal", m.turnChoices);
        // Indentation of the XML text is folded into single spaces.
        assertTrue(m.rules.startsWith("Tic-tac-toe on a 3x3 board. Cells are numbered 0 to 8,"));
        assertTrue(!m.rules.contains("  ") && !m.rules.contains("\n"));
    }

    @Test
    @DisplayName("Without <turn>, Jarvis never takes turns, and the persona may be left out")
    void noTurn() throws Exception {
        SessionManifest m = parse(session("", "<tools>show_hint</tools><events>moved</events>"));
        assertNull(m.turnEvent);
        assertNull(m.startTool);
        assertEquals("", m.persona);
    }

    @Test
    @DisplayName("A manifest that lists say itself is refused: say belongs to Jarvis")
    void sayIsReserved() {
        assertThrows(IllegalArgumentException.class, () ->
                parse(session("", "<tools>say, move</tools><events>moved</events>")));
    }

    @Test
    @DisplayName("The turn must use a declared event and a declared tool")
    void turnMustBeDeclared() {
        assertThrows(IllegalArgumentException.class, () -> parse(session("",
                "<tools>move</tools><events>moved</events><turn event=\"your_turn\" tool=\"move\"/>")));
        assertThrows(IllegalArgumentException.class, () -> parse(session("",
                "<tools>move</tools><events>your_turn</events><turn event=\"your_turn\" tool=\"jump\"/>")));
    }

    @Test
    @DisplayName("Text that would bloat every prompt is refused, not cut")
    void limits() {
        String longRules = "x".repeat(SessionManifest.MAX_RULES_CHARS + 1);
        assertThrows(IllegalArgumentException.class, () -> parse(
                "<session name=\"a\"><rules>" + longRules + "</rules><tools>t</tools>"
                        + "<events>e</events></session>"));
        assertThrows(IllegalArgumentException.class, () -> parse(session("",
                "<tools>a,b,c,d,e,f,g,h,i</tools><events>moved</events>")));
    }

    @Test
    @DisplayName("Names must be plain identifiers; the start tool is not a session tool")
    void names() {
        assertThrows(IllegalArgumentException.class, () -> parse(session("",
                "<tools>Move!</tools><events>moved</events>")));
        assertThrows(IllegalArgumentException.class, () -> parse(session(
                " start-tool=\"move\"", "<tools>move</tools><events>moved</events>")));
        assertThrows(IllegalArgumentException.class, () -> parse("<other/>"));
    }
}
