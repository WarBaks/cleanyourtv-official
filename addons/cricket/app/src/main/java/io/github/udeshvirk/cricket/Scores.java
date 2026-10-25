package io.github.udeshvirk.cricket;

import android.util.Log;

import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What the page shows, and when to ask ESPN again. The activity calls {@link #refresh} on a short
 * tick while it is on screen; a request only goes out when something is due, one at a time:
 *
 *  - today's scoreboard: every 5 minutes while a listed match is live, every 15 otherwise;
 *  - tomorrow's scoreboard: only when no followed match is live or starting soon, at most every 3 hours;
 *  - the big card's match summary: every minute while it is live, every 15 minutes while play has
 *    stopped (stumps, rain, a break), once for a result;
 *  - after a failure: 1, 2, 4, 8 minutes, then every 15; a 429 or 503 from ESPN: nothing for at
 *    least 30 minutes (longer if it sends Retry-After).
 *
 * Kept in memory for the life of the app process, so reopening the page shows the last scores at once.
 */
final class Scores {
    private static final String TAG = "Cricket";
    static final String SCOREBOARD =
            "https://site.web.api.espn.com/apis/personalized/v2/scoreboard/header?sport=cricket&region=in&lang=en";
    private static final String SUMMARY = "https://site.web.api.espn.com/apis/site/v2/sports/cricket/%s/summary?event=%s";

    static final long BOARD_LIVE = 5 * 60_000L, BOARD_IDLE = 15 * 60_000L, TOMORROW_TTL = 3 * 3600_000L;
    static final long DETAIL_LIVE = 60_000L, DETAIL_PAUSED = 15 * 60_000L, MAX_BACKOFF = 15 * 60_000L;
    static final long DETAIL_GAP = 10_000L;   // at least this between two summaries, however fast OK is pressed

    /** What the page draws. */
    static final class View {
        Feed.Match big;                            // the big card (with scorecard lines when known), or null
        boolean stand;                             // no followed match: big is the top listed match instead
        final List<Feed.Match> others = new ArrayList<>();
        long updated;                              // last successful read, 0 = never
        boolean failing;                           // the last attempt failed
        boolean loaded;                            // today's scoreboard was read at least once
    }

    private final Feed.Follow follow;
    private List<Feed.Match> today, tomorrow = new ArrayList<>();
    private String todayDay = "", tomorrowDay = "";
    private long todayTime, tomorrowTime, detailTime, detailAsked, lastAttempt, lastOk, pauseUntil;
    private Feed.Match detail;
    private String pinned = "";                    // a match chosen from the list for the big card
    private int fails;

    Scores(Feed.Follow follow) { this.follow = follow; }

    synchronized void pin(String id) { pinned = id == null ? "" : id; detailTime = 0; }
    synchronized String pinned() { return pinned; }

    /** Reads whatever is due (on a background thread); returns false when nothing was due. */
    boolean refresh(long now) {
        synchronized (this) {
            if (now < pauseUntil) return false;
            if (fails > 0 && now - lastAttempt < Math.min(MAX_BACKOFF, 60_000L << Math.min(fails - 1, 4))) return false;
        }
        boolean did = false;
        try {
            String day = Feed.ymd(now);
            if (today == null || !day.equals(todayDay) || now - todayTime >= boardEvery()) {
                did = true;
                mark(now);
                List<Feed.Match> t = Feed.scoreboard(Net.get(SCOREBOARD));
                synchronized (this) { today = t; todayDay = day; todayTime = now; }
            }
            String next = Feed.ymd(now + 24 * 3600_000L);
            if (Feed.wantTomorrow(today, follow, now) && (!next.equals(tomorrowDay) || now - tomorrowTime >= TOMORROW_TTL)) {
                did = true;
                mark(now);
                List<Feed.Match> t = Feed.scoreboard(Net.get(SCOREBOARD + "&dates=" + next));
                synchronized (this) { tomorrow = t; tomorrowDay = next; tomorrowTime = now; }
            }
            Feed.Match big = chosen(now);
            if (big != null && big.state != Feed.UPCOMING && detailDue(big, now)) {
                did = true;
                mark(now);
                synchronized (this) { detailAsked = now; }
                Feed.Match d = summary(big.id);
                synchronized (this) { detail = d; detailTime = now; }
            }
            if (did) synchronized (this) { fails = 0; lastOk = now; }
        } catch (Net.Busy b) {
            Log.w(TAG, "ESPN is busy; pausing " + b.retryMs / 60_000 + " min");
            synchronized (this) { pauseUntil = now + b.retryMs; fails = Math.min(fails + 1, 10); }
        } catch (Exception e) {
            Log.w(TAG, "read failed: " + e);
            synchronized (this) { fails = Math.min(fails + 1, 10); }
        }
        return did;
    }

    synchronized View view(long now) {
        View v = new View();
        v.loaded = today != null;
        v.updated = lastOk;
        v.failing = fails > 0 || now < pauseUntil;
        Feed.Match big = chosen(now);
        v.stand = big != null && pinned.isEmpty() && Feed.pick(all(), follow, now) == null;
        if (big != null && detail != null && detail.id.equals(big.id)
                && (detailTime >= todayTime || big.state == Feed.RESULT)) big = detail;   // the fresher copy, with lines
        v.big = big;
        if (today != null) v.others.addAll(Feed.others(today, follow, big == null ? "" : big.id, 4));
        return v;
    }

    // ------------------------------------------------------------------ internals

    private synchronized void mark(long now) { lastAttempt = now; }

    /**
     * The pinned match if it is still listed, else the followed one (today or tomorrow), else the
     * top of today's list (live first), so the big card is not empty on a day the team rests.
     */
    private synchronized Feed.Match chosen(long now) {
        if (today == null) return null;
        List<Feed.Match> all = all();
        if (!pinned.isEmpty()) for (Feed.Match m : all) if (m.id.equals(pinned)) return m;
        Feed.Match m = Feed.pick(all, follow, now);
        if (m != null) return m;
        List<Feed.Match> top = Feed.others(today, follow, "", 1);
        return top.isEmpty() ? null : top.get(0);
    }

    private synchronized List<Feed.Match> all() {
        List<Feed.Match> all = new ArrayList<>(today == null ? new ArrayList<>() : today);
        all.addAll(tomorrow);
        return all;
    }

    private synchronized long boardEvery() {
        if (today != null) for (Feed.Match m : today)
            if (m.state == Feed.LIVE && (follow.pref(m) >= 0 || Feed.international(m))) return BOARD_LIVE;
        return BOARD_IDLE;
    }

    private synchronized boolean detailDue(Feed.Match big, long now) {
        if (now - detailAsked < DETAIL_GAP) return false;
        if (detail == null || !detail.id.equals(big.id)) return true;
        if (big.state == Feed.RESULT) return detail.state != Feed.RESULT;   // once, after it ends
        long every = big.paused || detail.paused ? DETAIL_PAUSED : DETAIL_LIVE;
        return now - detailTime >= every;
    }

    private static Feed.Match summary(String id) throws Exception {
        int slash = id.indexOf('/');
        if (slash <= 0) throw new IllegalArgumentException("bad id " + id);
        String url = String.format(Locale.ROOT, SUMMARY,
                URLEncoder.encode(id.substring(0, slash), "UTF-8"), URLEncoder.encode(id.substring(slash + 1), "UTF-8"));
        return Feed.summary(Net.get(url), id);
    }
}
