package io.github.udeshvirk.cricket;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TimeZone;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * ESPN's public cricket JSON turned into small match objects, and the choice of which match to show.
 * No network and no Android UI here, so it is tested on the JVM (src/test).
 *
 * Two documents are read:
 *  - the scoreboard: every match of one day, with scores and a one-line status;
 *  - a match summary: one match with its players, for the batters at the crease, the bowler of the
 *    latest ball, or the top scorer and best bowling once it is over.
 */
final class Feed {
    static final int LIVE = 0, UPCOMING = 1, RESULT = 2;

    static final class Team {
        String id = "", name = "", code = "", score = "", overs = "", flag = "";
        boolean batting;
    }

    static final class Line {
        final String tag, name, figures;
        Line(String tag, String name, String figures) { this.tag = tag; this.name = name; this.figures = figures; }
    }

    static final class Match {
        String id = "", league = "", desc = "", status = "", batting = "";
        int state = UPCOMING;
        long start;
        boolean paused;   // live, but play has stopped (stumps, rain, a break)
        final List<Team> teams = new ArrayList<>();
        final List<Line> lines = new ArrayList<>();

        String title() { return title(desc, league); }
        String type() { return matchType(desc); }

        static String title(String desc, String league) {
            String[] parts = desc.split(", ");
            String match = parts[0].replaceAll("\\s*\\([^)]*\\)", "").trim();
            String series = stripYear(league);
            if (series.isEmpty() && parts.length > 1) series = parts[1].replaceAll("\\s+at\\s+.*$", "").trim();
            if (match.isEmpty()) return series;
            return series.isEmpty() ? match : match + "  ·  " + series;
        }
    }

    /** Who the big card follows (res/values/options.xml). */
    static final class Follow {
        final List<String> teams = new ArrayList<>();   // lower case, in order of preference
        final boolean women, ipl;

        Follow(String csv, boolean women, boolean ipl) {
            for (String t : csv.split(",")) {
                String n = t.trim().toLowerCase(Locale.ROOT);
                if (!n.isEmpty()) teams.add(n);
            }
            this.women = women;
            this.ipl = ipl;
        }

        /** 0, 1, 2... for a followed team (its place in the list), 100 for the IPL, -1 = not followed. */
        int pref(Match m) {
            int best = -1;
            for (Team t : m.teams) {
                String n = t.name.toLowerCase(Locale.ROOT);
                for (int i = 0; i < teams.size(); i++) {
                    if (n.equals(teams.get(i)) || (women && n.equals(teams.get(i) + " women")))
                        if (best < 0 || i < best) best = i;
                }
            }
            if (best >= 0) return best;
            return ipl && isIpl(m) ? 100 : -1;
        }
    }

    private static final Set<String> IPL_TEAMS = new HashSet<>(Arrays.asList(
            "chennai super kings", "mumbai indians", "royal challengers bengaluru", "royal challengers bangalore",
            "kolkata knight riders", "delhi capitals", "punjab kings", "rajasthan royals",
            "sunrisers hyderabad", "gujarat titans", "lucknow super giants"));
    /** The twelve Test nations: a match with one of them (or its A, women's or under-19 side) is listed. */
    private static final Set<String> FULL_MEMBERS = new HashSet<>(Arrays.asList(
            "india", "pakistan", "australia", "england", "new zealand", "south africa", "sri lanka",
            "bangladesh", "afghanistan", "zimbabwe", "ireland", "west indies"));
    private static final Pattern OVERS = Pattern.compile("\\(([\\d.]+)(?:/[\\d.]+)?\\s*ov");
    static final long UPCOMING_WINDOW = 18 * 3600_000L;   // the big card shows a match starting within this
    static final long RESULT_WINDOW = 20 * 3600_000L;     // and keeps a result while its start was within this

    // ------------------------------------------------------------------ the two documents

    /** Every match on a scoreboard document. */
    static List<Match> scoreboard(JSONObject j) throws Exception {
        List<Match> out = new ArrayList<>();
        JSONArray leagues = j.getJSONArray("sports").getJSONObject(0).getJSONArray("leagues");
        for (int i = 0; i < leagues.length(); i++) {
            JSONObject lg = leagues.getJSONObject(i);
            JSONArray events = lg.optJSONArray("events");
            for (int k = 0; events != null && k < events.length(); k++) {
                Match m = fromScoreboard(events.getJSONObject(k), lg);
                if (m != null) out.add(m);
            }
        }
        return out;
    }

    private static Match fromScoreboard(JSONObject ev, JSONObject lg) {
        JSONArray cs = ev.optJSONArray("competitors");
        if (cs == null || cs.length() < 2) return null;
        JSONObject fs = ev.optJSONObject("fullStatus");
        Match m = new Match();
        m.id = str(lg, "id") + "/" + str(ev, "id");
        m.state = state(str(ev, "status"));
        m.start = isoMs(str(ev, "date"));
        m.desc = str(ev, "description");
        m.league = str(lg, "name");
        m.status = fs == null ? "" : str(fs, "summary");
        m.paused = fs != null && paused(fs.optJSONObject("type"));
        m.batting = fs == null ? "" : str(fs, "battingTeamId");
        for (int i = 0; i < 2; i++) {
            JSONObject c = cs.optJSONObject(i);
            if (c == null) return null;
            m.teams.add(team(m, str(c, "id"), str(c, "displayName"), str(c, "abbreviation"), str(c, "score")));
        }
        return m;
    }

    /** One match from its summary document ("league/event" id), with its scorecard lines. */
    static Match summary(JSONObject j, String id) throws Exception {
        JSONObject h = j.getJSONObject("header");
        JSONObject c = h.getJSONArray("competitions").getJSONObject(0);
        JSONObject st = c.optJSONObject("status");
        JSONObject type = st == null ? null : st.optJSONObject("type");
        JSONObject lg = h.optJSONObject("league");
        Match m = new Match();
        m.id = id;
        m.state = state(type == null ? "" : str(type, "state"));
        m.start = isoMs(str(c, "date"));
        String desc = str(c, "description");
        m.desc = desc.isEmpty() ? str(h, "description") : desc;
        m.league = lg == null ? "" : str(lg, "name");
        m.status = st == null ? "" : str(st, "summary");
        m.paused = paused(type);
        m.batting = st == null ? "" : str(st, "battingTeamId");
        JSONArray cs = c.getJSONArray("competitors");
        for (int i = 0; i < 2; i++) {
            JSONObject t = cs.getJSONObject(i), team = t.getJSONObject("team");
            m.teams.add(team(m, str(t, "id"), str(team, "displayName"), str(team, "abbreviation"), str(t, "score")));
        }
        try {
            if (m.state == LIVE) liveLines(m, j, c);
            else if (m.state == RESULT) resultLines(m, j);
        } catch (Exception e) {
            m.lines.clear();   // keep the score without them
        }
        return m;
    }

    private static Team team(Match m, String id, String name, String abbr, String score) {
        Team t = new Team();
        t.id = id;
        t.name = name;
        t.code = !abbr.isEmpty() ? abbr.toUpperCase(Locale.ROOT)
                : name.length() <= 4 ? name.toUpperCase(Locale.ROOT) : name.substring(0, Math.min(3, name.length())).toUpperCase(Locale.ROOT);
        t.flag = flag(name);
        t.batting = m.state == LIVE && !m.batting.isEmpty() && m.batting.equals(id);
        int p = score.indexOf(" (");
        t.score = (p < 0 ? score : score.substring(0, p)).trim();
        Matcher om = OVERS.matcher(score);
        if (om.find()) t.overs = om.group(1) + " ov";
        return t;
    }

    /** Live, but ESPN says "Stumps", "Lunch", "Rain", "Innings break"... instead of "Live". */
    static boolean paused(JSONObject type) {
        if (type == null || !"in".equals(type.optString("state"))) return false;
        String desc = type.optString("description").trim();
        return !desc.isEmpty() && !desc.equalsIgnoreCase("live") && !desc.equalsIgnoreCase("in progress");
    }

    private static int state(String espn) {
        switch (espn) {
            case "in": return LIVE;
            case "post": return RESULT;
            default: return UPCOMING;
        }
    }

    // ------------------------------------------------------------------ scorecard lines

    /** Batters at the crease (not out in the current innings) and the bowler of the latest ball. */
    private static void liveLines(Match m, JSONObject sum, JSONObject comp) throws Exception {
        if (m.batting.isEmpty()) return;
        int period = currentPeriod(comp, m.batting);
        JSONArray rosters = sum.optJSONArray("rosters");
        int batters = 0;
        for (int r = 0; rosters != null && r < rosters.length(); r++) {
            JSONObject ro = rosters.getJSONObject(r);
            if (!m.batting.equals(ro.getJSONObject("team").optString("id"))) continue;
            JSONArray players = ro.getJSONArray("roster");
            for (int i = 0; i < players.length() && batters < 2; i++) {
                JSONObject pl = players.getJSONObject(i);
                Map<String, String> s = stats(pl, period);
                if (!"1".equals(s.get("batted")) || !"not out".equalsIgnoreCase(s.get("dismissalCard"))) continue;
                m.lines.add(new Line("BAT", name(pl), num(s.get("runs")) + " (" + num(s.get("ballsFaced")) + ")"));
                batters++;
            }
        }
        JSONObject latest = latestBall(comp.opt("commentaries"));
        JSONObject bowler = latest == null || latest.optInt("period", period) != period ? null : latest.optJSONObject("bowler");
        if (bowler != null && bowler.optJSONObject("athlete") != null) {
            JSONObject team = bowler.optJSONObject("team");
            if (team == null || !m.batting.equals(team.optString("id")))
                m.lines.add(new Line("BOWL", str(bowler.getJSONObject("athlete"), "displayName"),
                        bowler.optInt("wickets") + "/" + bowler.optInt("conceded") + " (" + overs(bowler.optString("overs")) + ")"));
        }
    }

    /** The commentary entry with the highest sequence; ESPN sends them as an object or an array. */
    private static JSONObject latestBall(Object cm) {
        List<JSONObject> all = new ArrayList<>();
        if (cm instanceof JSONObject) {
            JSONObject o = (JSONObject) cm;
            for (Iterator<String> it = o.keys(); it.hasNext(); ) {
                JSONObject e = o.optJSONObject(it.next());
                if (e != null) all.add(e);
            }
        } else if (cm instanceof JSONArray) {
            JSONArray a = (JSONArray) cm;
            for (int i = 0; i < a.length(); i++) if (a.optJSONObject(i) != null) all.add(a.optJSONObject(i));
        }
        JSONObject latest = null;
        for (JSONObject e : all) if (latest == null || e.optDouble("sequence") > latest.optDouble("sequence")) latest = e;
        return latest;
    }

    /** The innings the batting side is in now. */
    private static int currentPeriod(JSONObject comp, String batting) {
        int period = 1;
        JSONArray cs = comp.optJSONArray("competitors");
        for (int i = 0; cs != null && i < cs.length(); i++) {
            JSONObject c = cs.optJSONObject(i);
            if (c == null || !batting.equals(c.optString("id"))) continue;
            JSONArray ls = c.optJSONArray("linescores");
            for (int k = 0; ls != null && k < ls.length(); k++) {
                JSONObject l = ls.optJSONObject(k);
                if (l == null) continue;
                if (l.optInt("isCurrent") == 1) return l.optInt("period", period);
                period = Math.max(period, l.optInt("period", 1));
            }
        }
        return period;
    }

    /** Top scorer and best bowling across the match. */
    private static void resultLines(Match m, JSONObject sum) throws Exception {
        String topName = null, topFig = null, bestName = null, bestFig = null;
        int topRuns = -1, bestW = -1, bestR = Integer.MAX_VALUE;
        JSONArray rosters = sum.optJSONArray("rosters");
        for (int r = 0; rosters != null && r < rosters.length(); r++) {
            JSONArray players = rosters.getJSONObject(r).getJSONArray("roster");
            for (int i = 0; i < players.length(); i++) {
                JSONObject pl = players.getJSONObject(i);
                JSONArray ls = pl.optJSONArray("linescores");
                for (int k = 0; ls != null && k < ls.length(); k++) {
                    Map<String, String> s = stats(ls.getJSONObject(k));
                    if ("1".equals(s.get("batted")) && num(s.get("runs")) > topRuns) {
                        topRuns = num(s.get("runs"));
                        topName = name(pl);
                        topFig = topRuns + " (" + num(s.get("ballsFaced")) + ")";
                    }
                    if ("1".equals(s.get("bowled"))) {
                        int w = num(s.get("wickets")), c = num(s.get("conceded"));
                        if (w > bestW || (w == bestW && c < bestR)) {
                            bestW = w;
                            bestR = c;
                            bestName = name(pl);
                            bestFig = w + "/" + c + " (" + overs(s.get("overs")) + ")";
                        }
                    }
                }
            }
        }
        if (topName != null) m.lines.add(new Line("TOP", topName, topFig));
        if (bestName != null) m.lines.add(new Line("BEST", bestName, bestFig));
    }

    private static Map<String, String> stats(JSONObject player, int period) {
        JSONArray ls = player.optJSONArray("linescores");
        for (int k = 0; ls != null && k < ls.length(); k++) {
            JSONObject l = ls.optJSONObject(k);
            if (l != null && l.optInt("period") == period) return stats(l);
        }
        return new HashMap<>();
    }

    private static Map<String, String> stats(JSONObject linescore) {
        Map<String, String> out = new HashMap<>();
        JSONObject st = linescore.optJSONObject("statistics");
        JSONArray cats = st == null ? null : st.optJSONArray("categories");
        for (int i = 0; cats != null && i < cats.length(); i++) {
            JSONArray s = cats.optJSONObject(i) == null ? null : cats.optJSONObject(i).optJSONArray("stats");
            for (int k = 0; s != null && k < s.length(); k++) {
                JSONObject o = s.optJSONObject(k);
                if (o != null) out.put(o.optString("name"), o.optString("displayValue"));
            }
        }
        return out;
    }

    private static String name(JSONObject player) {
        JSONObject a = player.optJSONObject("athlete");
        return a == null ? "" : str(a, "displayName");
    }

    // ------------------------------------------------------------------ choosing

    /** Lower is better: live, then starting within 18 h, then a recent result. MAX_VALUE = not for the big card. */
    static int rank(Match m, Follow f, long now) {
        int pref = f.pref(m);
        if (pref < 0) return Integer.MAX_VALUE;
        if (m.state == LIVE) return pref;
        if (m.state == UPCOMING && m.start > 0 && m.start - now < UPCOMING_WINDOW && m.start - now > -3600_000L) return 1000 + pref;
        if (m.state == RESULT && m.start > 0 && now - m.start < RESULT_WINDOW) return 2000 + pref;
        return Integer.MAX_VALUE;
    }

    /** The followed match for the big card, or null. */
    static Match pick(List<Match> matches, Follow f, long now) {
        Match best = null;
        int bestRank = Integer.MAX_VALUE;
        for (Match m : matches) {
            int r = rank(m, f, now);
            if (r < bestRank) { bestRank = r; best = m; }
        }
        return best;
    }

    /** True when no followed match is live or starting soon: worth a look at tomorrow's fixtures. */
    static boolean wantTomorrow(List<Match> today, Follow f, long now) {
        Match m = pick(today, f, now);
        return m == null || rank(m, f, now) >= 1000;
    }

    /**
     * The side list: matches with a Test nation (or its A, women's or under-19 side) or a followed team,
     * live first, then by start time, without the one on the big card. At most max.
     */
    static List<Match> others(List<Match> matches, Follow f, String exceptId, int max) {
        List<Match> out = new ArrayList<>();
        for (Match m : matches) {
            if (m.id.equals(exceptId)) continue;
            if (f.pref(m) >= 0 || international(m)) out.add(m);
        }
        Collections.sort(out, (a, b) -> {
            int sa = a.state == LIVE ? 0 : a.state == UPCOMING ? 1 : 2, sb = b.state == LIVE ? 0 : b.state == UPCOMING ? 1 : 2;
            if (sa != sb) return Integer.compare(sa, sb);
            return sa == 2 ? Long.compare(b.start, a.start) : Long.compare(a.start, b.start);
        });
        return out.size() > max ? new ArrayList<>(out.subList(0, max)) : out;
    }

    static boolean international(Match m) {
        for (Team t : m.teams) if (FULL_MEMBERS.contains(base(t.name))) return true;
        return false;
    }

    static boolean isIpl(Match m) {
        if (m.league.toLowerCase(Locale.ROOT).contains("indian premier league")) return true;
        return m.teams.size() == 2 && IPL_TEAMS.contains(m.teams.get(0).name.toLowerCase(Locale.ROOT))
                && IPL_TEAMS.contains(m.teams.get(1).name.toLowerCase(Locale.ROOT));
    }

    /** "India Women", "India A", "India Under-19" -> "india". */
    private static String base(String team) {
        return team.toLowerCase(Locale.ROOT).replaceAll("\\s+(women|a|u19|under-19)$", "").trim();
    }

    /** Bundled flat flag for a national side (res/drawable-nodpi/flag_*.png), or "". */
    static String flag(String team) {
        switch (base(team)) {
            case "india": return "in";
            case "pakistan": return "pk";
            case "australia": return "au";
            case "england": return "gb_eng";
            case "new zealand": return "nz";
            case "south africa": return "za";
            case "sri lanka": return "lk";
            case "bangladesh": return "bd";
            case "afghanistan": return "af";
            case "zimbabwe": return "zw";
            case "ireland": return "ie";
            case "netherlands": return "nl";
            case "scotland": return "gb_sct";
            case "nepal": return "np";
            case "united arab emirates": case "uae": return "ae";
            case "united states of america": case "usa": case "united states": return "us";
            case "oman": return "om";
            case "namibia": return "na";
            case "canada": return "ca";
            default: return "";
        }
    }

    // ------------------------------------------------------------------ helpers

    static String matchType(String desc) {
        Matcher mt = Pattern.compile("\\b(T20I|ODI|Test|T20)\\b").matcher(desc.split(", ")[0]);
        return mt.find() ? mt.group(1) : "";
    }

    private static String str(JSONObject o, String k) { return o.isNull(k) ? "" : o.optString(k).trim(); }

    private static int num(String s) {
        try { return s == null || s.isEmpty() ? 0 : (int) Double.parseDouble(s); } catch (Exception e) { return 0; }
    }

    /** "1.0" -> "1", "3.4" -> "3.4". */
    private static String overs(String o) {
        if (o == null || o.isEmpty()) return "0";
        return o.endsWith(".0") ? o.substring(0, o.length() - 2) : o;
    }

    /** "India tour of New Zealand 2026/27" -> "India tour of New Zealand". */
    static String stripYear(String s) { return s.replaceAll("\\s*\\d{4}(?:[-/]\\d{2,4})?$", "").trim(); }

    static long isoMs(String s) {
        if (s.isEmpty()) return 0;
        for (String fmt : new String[] {"yyyy-MM-dd'T'HH:mm:ss'Z'", "yyyy-MM-dd'T'HH:mm'Z'"}) {
            try {
                SimpleDateFormat f = new SimpleDateFormat(fmt, Locale.ROOT);
                f.setTimeZone(TimeZone.getTimeZone("UTC"));
                Date d = f.parse(s);
                if (d != null) return d.getTime();
            } catch (Exception ignored) { }
        }
        return 0;
    }

    /** yyyyMMdd in the TV's time zone, for the scoreboard's dates parameter. */
    static String ymd(long t) { return new SimpleDateFormat("yyyyMMdd", Locale.ROOT).format(new Date(t)); }

    private Feed() {}
}
