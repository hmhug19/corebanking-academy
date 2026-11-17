package vn.com.coreacademy.chatroom.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class RoomBoardMatchTest {
    private RoomBoardMatch match(boolean xiangqi) {
        return new RoomBoardMatch(xiangqi, new RoomBoardMatch.Player("a", "An"),
            new RoomBoardMatch.Player("b", "Bình"), 10, 300_000, 3000, 1000);
    }

    private void move(RoomBoardMatch match, int from, int to, long now) {
        String user = match.players[match.position.turn].userId();
        assertTrue(match.move(user, match.id.toString(), match.history.size(), from, to, "", now));
    }

    @Test
    void versionWrongAccountAndDuplicateCannotAlterBoardOrIncrementTwice() {
        var match = match(false);
        assertFalse(match.move("viewer", match.id.toString(), 0, 52, 36, "", 2000));
        assertFalse(match.move("b", match.id.toString(), 0, 52, 36, "", 2000));
        assertFalse(match.move("a", "wrong-match", 0, 52, 36, "", 2000));
        move(match, 52, 36, 2000);
        assertEquals(302000, match.clock(0, 2000));
        assertFalse(match.move("a", match.id.toString(), 0, 51, 35, "", 2500));
        assertEquals(1, match.history.size());
        assertEquals(302000, match.clock(0, 2500));
    }

    @Test
    void clockRunsWithoutConnectionsAndNoMoveAtExactDeadline() {
        var match = match(false);
        assertFalse(match.move("a", match.id.toString(), 0, 52, 36, "", 301000));
        assertEquals(1, match.winner);
        assertEquals("timeout", match.reason);
        assertEquals(0, match.history.size());
    }

    @Test
    void checkmateAndAutomaticDrawPrecedence() {
        var match = match(false);
        move(match, 53, 45, 2000);
        move(match, 12, 28, 3000);
        move(match, 54, 38, 4000);
        match.position.quiet = 149;
        move(match, 3, 39, 5000);
        assertEquals("checkmate", match.reason);
        assertEquals(1, match.winner);
    }

    @Test
    void threefoldClaimBasedOnIntendedMoveDoesNotPlayIt() {
        var match = match(false);
        int[][] cycle = {{62,45},{6,21},{45,62},{21,6}};
        for (int i = 0; i < 7; i++) {
            move(match, cycle[i % 4][0], cycle[i % 4][1], 2000 + i * 100);
        }
        assertTrue(match.action("b", match.id.toString(), 7, "claim-draw", 21, 6, "", 3000));
        assertEquals("threefold", match.reason);
        assertEquals(7, match.history.size());
    }

    @Test
    void fivefoldIsAutomaticButThreefoldIsNot() {
        var match = match(false);
        int[][] cycle = {{62,45},{6,21},{45,62},{21,6}};
        for (int i = 0; i < 16; i++) {
            move(match, cycle[i % 4][0], cycle[i % 4][1], 2000 + i * 100);
            if (i == 7) {
                assertNull(match.reason);
            }
        }
        assertEquals("fivefold", match.reason);
    }

    @Test
    void erroneousFideClaimAddsTwoMinutesAndMakesIntendedLegalMove() {
        var match = match(false);
        assertTrue(match.action("a", match.id.toString(), 0, "claim-draw", 52, 36, "", 2000));
        assertEquals(420000, match.clock(1, 2000));
        assertEquals(1, match.history.size());
        assertNull(match.reason);
    }

    @Test
    void fiftyMoveClaimAndSeventyFiveAutomaticAndTimeoutWithBareKing() {
        var match = match(false);
        match.position.quiet = 100;
        assertTrue(match.action("a", match.id.toString(), 0, "claim-draw", null, null, "", 2000));
        assertEquals("fifty-moves", match.reason);
        match = match(false);
        match.position.quiet = 149;
        move(match, 62, 45, 2000);
        assertEquals("seventy-five-moves", match.reason);
        match = match(false);
        match.position = RoomBoardPositionTest.fen(false, "7k/8/8/8/8/8/8/R6K", 0);
        assertTrue(match.expire(301000));
        assertNull(match.winner);
    }

    @Test
    void wxfDiagram1SinglePerpetualCheckWarnsThenLosesIfContinued() {
        var match = match(true);
        // WXF 2018, Diagram 1, page 40 (PDF page 48).
        match.position = RoomBoardPositionTest.fen(true,
            "c2a1k3/2c1a2R1/e6P1/8p/9/9/9/9/4K4/9", 0);
        match.positions.clear();
        match.positions.add(new RoomBoardPosition(match.position));
        match.repetitions.clear();
        match.repetitions.put(match.position.key(), 1);
        int[][] cycle = {{16,7},{5,14},{7,16},{14,5}};
        for (int i = 0; i < 8; i++) {
            move(match, cycle[i % 4][0], cycle[i % 4][1], 2000 + i * 100);
        }
        assertEquals(0, match.mustChange);
        assertNull(match.reason);
        for (int i = 0; i < 4; i++) {
            move(match, cycle[i][0], cycle[i][1], 3000 + i * 100);
        }
        assertEquals("perpetual-check", match.reason);
        assertEquals(1, match.winner);
    }

    @Test
    void drawOfferSurvivesOfferersMoveAndOpponentsMoveDeclinesIt() {
        var match = match(false);
        move(match, 52, 36, 2000);
        move(match, 12, 28, 3000);
        assertTrue(match.action("a", match.id.toString(), 2, "offer-draw", null, null, "", 3100));
        move(match, 62, 45, 3200);
        assertEquals(0, match.drawOffer);
        move(match, 6, 21, 3300);
        assertNull(match.drawOffer);
        assertTrue(match.action("a", match.id.toString(), 4, "offer-draw", null, null, "", 3400));
        assertTrue(match.action("b", match.id.toString(), 4, "accept-draw", null, null, "", 3500));
        assertEquals("agreement", match.reason);
    }

    @Test
    void wxfIdleRepetitionNeedsFourOccurrencesUnderArticle32B() {
        var match = match(true);
        int[][] cycle = {{82,63},{1,20},{63,82},{20,1}};
        for (int i = 0; i < 12; i++) {
            move(match, cycle[i % 4][0], cycle[i % 4][1], 2000 + i * 100);
            if (i == 7) {
                assertNull(match.reason);
            }
        }
        assertEquals("repetition", match.reason);
        assertNull(match.winner);
    }

    @Test
    void xiangqiNaturalMovecountUsesOneHundredPliesAndFalseVerificationCostsFiveMinutes() {
        var match = match(true);
        match.position.quiet = 99;
        move(match, 82, 63, 2000);
        assertEquals("natural-movecount", match.reason);
        match = match(true);
        assertTrue(match.action("a", match.id.toString(), 0, "claim-draw", null, null, "", 2000));
        assertEquals("invalid-claim", match.reason);
        assertEquals(1, match.winner);
    }
}
