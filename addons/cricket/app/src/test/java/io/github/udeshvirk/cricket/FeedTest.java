package io.github.udeshvirk.cricket;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/** The feed parsing and the choice of match, on small documents shaped like ESPN's. */
public class FeedTest {
    private static final long NOW = Feed.isoMs("2026-10-25T10:00:00Z");
    private final Feed.Follow india = new Feed.Follow("India", false, true);

    private static JSONObject team(String id, String name, String abbr, String score) throws Exception {
        return new JSONObject().put("id", id).put("displayName", name).put("abbreviation", abbr).put("score", score);
    }

    private static JSONObject event(String id, String status, String date, String desc, String summary,
                                    String typeDesc, String batting, JSONObject a, JSONObject b) throws Exception {
        return new JSONObject().put("id", id).put("status", status).put("date", date).put("description", desc)
                .put("fullStatus", new JSONObject().put("summary", summary).put("battingTeamId", batting)
                        .put("type", new JSONObject().put("state", status).put("description", typeDesc)))
                .put("competitors", new JSONArray().put(a).put(b));
    }

    private static JSONObject board(JSONObject... leagues) throws Exception {
        return new JSONObject().put("sports", new JSONArray().put(new JSONObject().put("leagues", new JSONArray(leagues))));
    }

    private static JSONObject league(String id, String name, JSONObject... events) throws Exception {
        return new JSONObject().put("id", id).put("name", name).put("events", new JSONArray(events));
    }

    private JSONObject sampleBoard() throws Exception {
        return board(
                league("100", "India tour of New Zealand 2026/27",
                        event("1", "in", "2026-10-25T07:00:00Z", "2nd T20I (N), India tour of New Zealand at Auckland",
                                "India need 42 runs from 30 balls", "Live", "11",
                                team("10", "New Zealand", "NZ", "186/6 (20/20 ov)"),
                                team("11", "India", "IND", "145/3 (15/20 ov)"))),
                league("200", "Pakistan ODI Tri-Series",
                        event("2", "pre", "2026-10-25T13:00:00Z", "3rd Match, Pakistan ODI Tri-Series at Lahore",
                                "Starts at 18:00 local time", "Scheduled", "",
                                team("20", "Pakistan", "PAK", ""), team("21", "England", "ENG", ""))),
                league("300", "National Cricket League",
                        event("3", "in", "2026-10-25T03:30:00Z", "Round 4, National Cricket League at Sylhet",
                                "Stumps", "Stumps", "31",
                                team("30", "Sylhet Division", "", "299/4 (87 ov)"), team("31", "Khulna Division", "", ""))));
    }

    @Test public void readsTheScoreboard() throws Exception {
        List<Feed.Match> all = Feed.scoreboard(sampleBoard());
        assertEquals(3, all.size());
        Feed.Match m = all.get(0);
        assertEquals("100/1", m.id);
        assertEquals(Feed.LIVE, m.state);
        assertEquals("2nd T20I  ·  India tour of New Zealand", m.title());
        assertEquals("T20I", m.type());
        assertEquals("186/6", m.teams.get(0).score);
        assertEquals("20 ov", m.teams.get(0).overs);
        assertEquals("15 ov", m.teams.get(1).overs);
        assertTrue(m.teams.get(1).batting);
        assertFalse(m.teams.get(0).batting);
        assertEquals("in", m.teams.get(1).flag);
        assertEquals("nz", m.teams.get(0).flag);
        assertFalse(m.paused);
        assertTrue(all.get(2).paused);   // "Stumps"
        assertEquals("SYL", all.get(2).teams.get(0).code);   // no abbreviation: first three letters
    }

    @Test public void picksTheFollowedLiveMatch() throws Exception {
        List<Feed.Match> all = Feed.scoreboard(sampleBoard());
        assertEquals("100/1", Feed.pick(all, india, NOW).id);
        assertNull(Feed.pick(all, new Feed.Follow("Australia", false, false), NOW));
        // a live match beats one that has not started, whatever the order of the followed teams
        assertEquals("100/1", Feed.pick(all, new Feed.Follow("England, India", false, false), NOW).id);
        assertEquals("200/2", Feed.pick(all, new Feed.Follow("England", false, false), NOW).id);
        assertFalse(Feed.wantTomorrow(all, india, NOW));
        assertTrue(Feed.wantTomorrow(all, new Feed.Follow("Australia", false, false), NOW));
    }

    @Test public void womenAndIplOnlyWhenAsked() throws Exception {
        JSONObject b = board(
                league("1", "Zimbabwe Women tour of India", event("5", "pre", "2026-10-25T12:00:00Z", "1st ODI", "", "", "",
                        team("1", "India Women", "IND-W", ""), team("2", "Zimbabwe Women", "ZIM-W", ""))),
                league("2", "Indian Premier League 2027", event("6", "pre", "2026-10-25T14:00:00Z", "Match 1", "", "", "",
                        team("3", "Mumbai Indians", "MI", ""), team("4", "Chennai Super Kings", "CSK", ""))));
        List<Feed.Match> all = Feed.scoreboard(b);
        assertEquals("2/6", Feed.pick(all, india, NOW).id);   // IPL, not India Women
        assertEquals("1/5", Feed.pick(all, new Feed.Follow("India", true, true), NOW).id);
        assertNull(Feed.pick(all, new Feed.Follow("India", false, false), NOW));
    }

    @Test public void oldResultsAndFarFixturesAreNotPicked() throws Exception {
        JSONObject b = board(league("1", "Series",
                event("7", "post", "2026-10-23T07:00:00Z", "1st T20I", "India won by 5 wickets", "Final", "",
                        team("1", "India", "IND", "150/5"), team("2", "Australia", "AUS", "149/8")),
                event("8", "pre", "2026-10-27T07:00:00Z", "2nd T20I", "", "", "",
                        team("1", "India", "IND", ""), team("2", "Australia", "AUS", ""))));
        assertNull(Feed.pick(Feed.scoreboard(b), india, NOW));
    }

    @Test public void listsInternationalMatchesLiveFirst() throws Exception {
        List<Feed.Match> all = Feed.scoreboard(sampleBoard());
        List<Feed.Match> others = Feed.others(all, india, "100/1", 6);
        assertEquals(1, others.size());   // Pakistan v England; the domestic match is left out
        assertEquals("200/2", others.get(0).id);
        assertEquals(2, Feed.others(all, india, "", 6).size());
        assertEquals("100/1", Feed.others(all, india, "", 6).get(0).id);   // live first
    }

    private static JSONObject player(String name, int period, String... kv) throws Exception {
        JSONArray stats = new JSONArray();
        for (int i = 0; i < kv.length; i += 2) stats.put(new JSONObject().put("name", kv[i]).put("displayValue", kv[i + 1]));
        return new JSONObject().put("athlete", new JSONObject().put("displayName", name))
                .put("linescores", new JSONArray().put(new JSONObject().put("period", period)
                        .put("statistics", new JSONObject().put("categories", new JSONArray().put(new JSONObject().put("stats", stats))))));
    }

    @Test public void liveSummaryHasBattersAndBowler() throws Exception {
        JSONObject comp = new JSONObject()
                .put("date", "2026-10-25T07:00:00Z").put("description", "2nd T20I")
                .put("status", new JSONObject().put("summary", "India need 42 runs from 30 balls").put("battingTeamId", "11")
                        .put("type", new JSONObject().put("state", "in").put("description", "Live")))
                .put("competitors", new JSONArray()
                        .put(new JSONObject().put("id", "10").put("score", "186/6 (20 ov)")
                                .put("team", new JSONObject().put("displayName", "New Zealand").put("abbreviation", "NZ")))
                        .put(new JSONObject().put("id", "11").put("score", "145/3 (15/20 ov)")
                                .put("linescores", new JSONArray().put(new JSONObject().put("period", 2).put("isCurrent", 1)))
                                .put("team", new JSONObject().put("displayName", "India").put("abbreviation", "IND"))))
                .put("commentaries", new JSONArray()
                        .put(new JSONObject().put("sequence", 1).put("period", 2).put("bowler", new JSONObject()
                                .put("athlete", new JSONObject().put("displayName", "Old Bowler"))))
                        .put(new JSONObject().put("sequence", 9).put("period", 2).put("bowler", new JSONObject()
                                .put("athlete", new JSONObject().put("displayName", "Matt Henry"))
                                .put("team", new JSONObject().put("id", "10"))
                                .put("wickets", 1).put("conceded", 28).put("overs", "3.0"))));
        JSONObject sum = new JSONObject()
                .put("header", new JSONObject().put("league", new JSONObject().put("name", "India tour of New Zealand 2026/27"))
                        .put("competitions", new JSONArray().put(comp)))
                .put("rosters", new JSONArray()
                        .put(new JSONObject().put("team", new JSONObject().put("id", "11")).put("roster", new JSONArray()
                                .put(player("Out Batter", 2, "batted", "1", "dismissalCard", "c Smith b Jones", "runs", "40", "ballsFaced", "30"))
                                .put(player("Suryakumar Yadav", 2, "batted", "1", "dismissalCard", "not out", "runs", "58", "ballsFaced", "31"))
                                .put(player("Hardik Pandya", 2, "batted", "1", "dismissalCard", "not out", "runs", "21", "ballsFaced", "12"))
                                .put(player("Yet To Bat", 2, "batted", "0")))));
        Feed.Match m = Feed.summary(sum, "100/1");
        assertEquals(Feed.LIVE, m.state);
        assertEquals("2nd T20I  ·  India tour of New Zealand", m.title());
        assertEquals(3, m.lines.size());
        assertEquals("Suryakumar Yadav", m.lines.get(0).name);
        assertEquals("58 (31)", m.lines.get(0).figures);
        assertEquals("Hardik Pandya", m.lines.get(1).name);
        assertEquals("BOWL", m.lines.get(2).tag);
        assertEquals("Matt Henry", m.lines.get(2).name);
        assertEquals("1/28 (3)", m.lines.get(2).figures);
    }

    @Test public void resultSummaryHasTopScorerAndBestBowling() throws Exception {
        JSONObject comp = new JSONObject().put("date", "2026-10-25T07:00:00Z").put("description", "1st ODI")
                .put("status", new JSONObject().put("summary", "India won by 5 wickets")
                        .put("type", new JSONObject().put("state", "post")))
                .put("competitors", new JSONArray()
                        .put(new JSONObject().put("id", "1").put("score", "250/5").put("team", new JSONObject().put("displayName", "India")))
                        .put(new JSONObject().put("id", "2").put("score", "249/9").put("team", new JSONObject().put("displayName", "Pakistan"))));
        JSONObject sum = new JSONObject()
                .put("header", new JSONObject().put("competitions", new JSONArray().put(comp)))
                .put("rosters", new JSONArray().put(new JSONObject().put("team", new JSONObject().put("id", "1")).put("roster", new JSONArray()
                        .put(player("Virat Kohli", 2, "batted", "1", "runs", "101", "ballsFaced", "99"))
                        .put(player("Jasprit Bumrah", 1, "bowled", "1", "wickets", "4", "conceded", "30", "overs", "10.0"))
                        .put(player("Other Bowler", 1, "bowled", "1", "wickets", "4", "conceded", "45", "overs", "10.0")))));
        Feed.Match m = Feed.summary(sum, "1/2");
        assertEquals(Feed.RESULT, m.state);
        assertEquals("TOP", m.lines.get(0).tag);
        assertEquals("101 (99)", m.lines.get(0).figures);
        assertEquals("Jasprit Bumrah", m.lines.get(1).name);
        assertEquals("4/30 (10)", m.lines.get(1).figures);
        assertEquals("PAK", m.teams.get(1).code);   // no abbreviation: first three letters
    }

    @Test public void aBrokenScorecardKeepsTheScore() throws Exception {
        JSONObject comp = new JSONObject().put("date", "2026-10-25T07:00:00Z")
                .put("status", new JSONObject().put("battingTeamId", "1").put("type", new JSONObject().put("state", "in")))
                .put("competitors", new JSONArray()
                        .put(new JSONObject().put("id", "1").put("score", "10/0").put("team", new JSONObject().put("displayName", "India")))
                        .put(new JSONObject().put("id", "2").put("team", new JSONObject().put("displayName", "Ireland"))));
        JSONObject sum = new JSONObject().put("header", new JSONObject().put("competitions", new JSONArray().put(comp)))
                .put("rosters", "not an array");
        Feed.Match m = Feed.summary(sum, "1/2");
        assertEquals("10/0", m.teams.get(0).score);
        assertTrue(m.lines.isEmpty());
    }
}
