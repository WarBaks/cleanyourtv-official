package io.github.udeshvirk.cricket;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.text.format.DateFormat;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;

/**
 * Full-screen scores: the followed team's match in a big card (live with the batters and bowler,
 * the next one with its start time, or the result with the top scorer and best bowling), and
 * today's other international matches beside it. OK on a listed match moves it into the big card;
 * Back puts the followed match back, then leaves.
 *
 * Reads ESPN only while this page is on screen (see Scores for how often). Nothing runs in the
 * background, and leaving the page stops the tick.
 */
public class CricketActivity extends Activity {
    private static final long TICK = 15_000L;
    private static final int LIVE_RED = 0xFFE5484D, AMBER = 0xFFFFD27A;

    private static Scores scores;   // survives the activity, so a reopen shows the last scores at once
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean resumed, busy;
    private float dp;

    private TextView updated;
    private LinearLayout big, list;
    private final List<View> rows = new ArrayList<>();
    private final List<String> rowIds = new ArrayList<>();
    private int focus = -1;   // focused row in the list, -1 = none

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b);
        dp = getResources().getDisplayMetrics().density;
        if (scores == null) {
            scores = new Scores(new Feed.Follow(getString(R.string.follow_teams),
                    getResources().getBoolean(R.bool.follow_women), getResources().getBoolean(R.bool.follow_ipl)));
        }
        buildUi();
    }

    @Override protected void onResume() {
        super.onResume();
        resumed = true;
        render();
        tick.run();
    }

    @Override protected void onPause() {
        resumed = false;
        main.removeCallbacks(tick);
        super.onPause();
    }

    private final Runnable tick = new Runnable() {
        @Override public void run() {
            main.removeCallbacks(this);
            if (!resumed) return;
            if (!busy) {
                busy = true;
                new Thread(() -> {
                    boolean did = scores.refresh(System.currentTimeMillis());
                    main.post(() -> { busy = false; if (did && resumed) render(); });
                }, "cricket").start();
            }
            main.postDelayed(this, TICK);
            render();   // keeps "updated" and start times current between reads
        }
    };

    // ------------------------------------------------------------------ keys

    @Override public boolean dispatchKeyEvent(KeyEvent e) {
        if (e.getAction() != KeyEvent.ACTION_DOWN) return super.dispatchKeyEvent(e);
        switch (e.getKeyCode()) {
            case KeyEvent.KEYCODE_DPAD_DOWN:
                if (!rows.isEmpty()) { focus = Math.min(focus + 1, rows.size() - 1); paintFocus(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_UP:
                if (focus >= 0) { focus--; paintFocus(); }
                return true;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
            case KeyEvent.KEYCODE_NUMPAD_ENTER:
                if (focus >= 0 && focus < rowIds.size()) {
                    scores.pin(rowIds.get(focus));
                    focus = -1;
                    render();
                    tick.run();   // read its scorecard now
                }
                return true;
            case KeyEvent.KEYCODE_BACK:
                if (focus >= 0) { focus = -1; paintFocus(); return true; }
                if (!scores.pinned().isEmpty()) { scores.pin(null); render(); tick.run(); return true; }
                break;
        }
        return super.dispatchKeyEvent(e);
    }

    // ------------------------------------------------------------------ layout

    private int px(float v) { return Math.round(v * dp); }

    private TextView text(float sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setTextSize(sp);
        t.setTextColor(color);
        t.setTypeface(Typeface.create("sans-serif", bold ? Typeface.BOLD : Typeface.NORMAL));
        t.setIncludeFontPadding(false);
        t.setSingleLine(true);
        t.setEllipsize(TextUtils.TruncateAt.END);
        return t;
    }

    private static GradientDrawable box(int fill, int stroke, float radius, float strokeW) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(radius);
        if (stroke != 0) g.setStroke(Math.max(1, Math.round(strokeW)), stroke);
        return g;
    }

    private void buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackground(new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{0xFF0B2A1E, 0xFF07110D, 0xFF050708}));

        TextView head = text(30, Color.WHITE, true);
        head.setText("Cricket");
        FrameLayout.LayoutParams hl = new FrameLayout.LayoutParams(-2, -2);
        hl.leftMargin = px(56);
        hl.topMargin = px(40);
        root.addView(head, hl);

        updated = text(14, 0x99FFFFFF, false);
        FrameLayout.LayoutParams ul = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.END);
        ul.rightMargin = px(56);
        ul.topMargin = px(52);
        root.addView(updated, ul);

        LinearLayout cols = new LinearLayout(this);
        cols.setOrientation(LinearLayout.HORIZONTAL);
        FrameLayout.LayoutParams cl = new FrameLayout.LayoutParams(-1, -1);
        cl.leftMargin = cl.rightMargin = px(56);
        cl.topMargin = px(100);
        cl.bottomMargin = px(40);
        root.addView(cols, cl);

        big = new LinearLayout(this);
        big.setOrientation(LinearLayout.VERTICAL);
        big.setPadding(px(32), px(22), px(32), px(22));
        big.setBackground(box(0xB3101C17, 0x26FFFFFF, px(22), px(1)));
        cols.addView(big, new LinearLayout.LayoutParams(0, -2, 1.75f));

        LinearLayout side = new LinearLayout(this);
        side.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(0, -1, 1f);
        sl.leftMargin = px(28);
        cols.addView(side, sl);
        TextView th = text(13, 0x80FFFFFF, true);
        th.setText("ALSO TODAY");
        th.setLetterSpacing(0.12f);
        side.addView(th);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(-1, -2);
        ll.topMargin = px(10);
        side.addView(list, ll);

        setContentView(root);
    }

    // ------------------------------------------------------------------ drawing

    private void render() {
        long now = System.currentTimeMillis();
        Scores.View v = scores.view(now);

        if (v.updated == 0) updated.setText(v.failing ? "Can't reach the scores. Retrying." : "Loading scores…");
        else updated.setText((v.failing ? "Not updating since " : "Updated ") + time(v.updated) + "   ·   Data: ESPN");
        updated.setTextColor(v.failing ? AMBER : 0x99FFFFFF);

        big.removeAllViews();
        if (v.big != null && v.stand) {
            TextView n = text(14, 0x80FFFFFF, false);
            n.setText("No match for " + teams() + " today or tomorrow.");
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(-1, -2);
            nl.bottomMargin = px(12);
            big.addView(n, nl);
        }
        if (v.big != null) drawBig(v.big, now);
        else if (v.loaded) drawNothing();

        String keep = focus >= 0 && focus < rowIds.size() ? rowIds.get(focus) : null;
        list.removeAllViews();
        rows.clear();
        rowIds.clear();
        for (Feed.Match m : v.others) {
            View r = row(m, now);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.bottomMargin = px(10);
            list.addView(r, lp);
            rows.add(r);
            rowIds.add(m.id);
        }
        if (v.loaded && v.others.isEmpty()) {
            TextView t = text(15, 0x80FFFFFF, false);
            t.setText("No other international matches.");
            list.addView(t);
        }
        focus = keep == null ? -1 : rowIds.indexOf(keep);
        paintFocus();
    }

    private void drawNothing() {
        TextView t = text(24, Color.WHITE, false);
        t.setSingleLine(false);
        t.setText("No match for " + teams() + " today or tomorrow.");
        big.addView(t);
        TextView s = text(16, 0x99FFFFFF, false);
        s.setSingleLine(false);
        s.setText("Pick a match on the right with OK to follow it here.");
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
        lp.topMargin = px(12);
        big.addView(s, lp);
    }

    private void drawBig(Feed.Match m, long now) {
        LinearLayout head = new LinearLayout(this);
        head.setGravity(Gravity.CENTER_VERTICAL);
        head.addView(badge(m, now, 13));
        TextView title = text(17, 0xB3FFFFFF, false);
        title.setText(m.title());
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, -2, 1);
        tl.leftMargin = px(14);
        head.addView(title, tl);
        big.addView(head);

        boolean anyBatting = false;
        for (Feed.Team t : m.teams) anyBatting |= t.batting;
        for (int i = 0; i < m.teams.size(); i++) {
            Feed.Team t = m.teams.get(i);
            int c = anyBatting && !t.batting ? 0x99FFFFFF : Color.WHITE;
            LinearLayout r = new LinearLayout(this);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.addView(flag(t, 22), new LinearLayout.LayoutParams(px(48), px(32)));
            TextView name = text(26, c, false);
            name.setText(t.name);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2, 1);
            nl.leftMargin = px(16);
            r.addView(name, nl);
            TextView score = text(32, c, true);
            score.setText(t.score.isEmpty() ? "–" : t.score);
            r.addView(score);
            TextView ov = text(16, 0x80FFFFFF, false);
            ov.setText(t.overs);
            ov.setGravity(Gravity.END);
            LinearLayout.LayoutParams ol = new LinearLayout.LayoutParams(px(76), -2);
            ol.leftMargin = px(12);
            r.addView(ov, ol);
            LinearLayout.LayoutParams rl = new LinearLayout.LayoutParams(-1, px(44));
            rl.topMargin = px(i == 0 ? 16 : 6);
            big.addView(r, rl);
        }

        String status = m.state == Feed.UPCOMING ? startsLine(m, now) : m.status;
        if (!status.isEmpty()) {
            TextView s = text(19, m.state == Feed.LIVE ? AMBER : 0xCCFFFFFF, false);
            s.setText(status);
            LinearLayout.LayoutParams sl = new LinearLayout.LayoutParams(-1, -2);
            sl.topMargin = px(14);
            big.addView(s, sl);
        }

        if (!m.lines.isEmpty()) {
            View div = new View(this);
            div.setBackgroundColor(0x1FFFFFFF);
            LinearLayout.LayoutParams dl = new LinearLayout.LayoutParams(-1, Math.max(1, px(1)));
            dl.topMargin = px(14);
            dl.bottomMargin = px(6);
            big.addView(div, dl);
            for (Feed.Line l : m.lines) {
                LinearLayout r = new LinearLayout(this);
                r.setGravity(Gravity.CENTER_VERTICAL);
                TextView tag = text(13, 0x80FFFFFF, true);
                tag.setText(l.tag);
                tag.setLetterSpacing(0.12f);
                r.addView(tag, new LinearLayout.LayoutParams(px(64), -2));
                TextView n = text(20, 0xE6FFFFFF, false);
                n.setText(l.name);
                r.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
                TextView f = text(20, Color.WHITE, true);
                f.setText(l.figures);
                r.addView(f);
                big.addView(r, new LinearLayout.LayoutParams(-1, px(32)));
            }
        }
    }

    private View row(Feed.Match m, long now) {
        LinearLayout r = new LinearLayout(this);
        r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(px(16), px(10), px(16), px(10));
        LinearLayout top = new LinearLayout(this);
        top.setGravity(Gravity.CENTER_VERTICAL);
        top.addView(badge(m, now, 10));
        TextView lg = text(12, 0x80FFFFFF, false);
        lg.setText(Feed.stripYear(m.league));
        LinearLayout.LayoutParams ll = new LinearLayout.LayoutParams(0, -2, 1);
        ll.leftMargin = px(10);
        top.addView(lg, ll);
        r.addView(top);
        for (Feed.Team t : m.teams) {
            LinearLayout line = new LinearLayout(this);
            line.setGravity(Gravity.CENTER_VERTICAL);
            line.addView(flag(t, 10), new LinearLayout.LayoutParams(px(24), px(16)));
            TextView n = text(15, Color.WHITE, false);
            n.setText(t.name);
            LinearLayout.LayoutParams nl = new LinearLayout.LayoutParams(0, -2, 1);
            nl.leftMargin = px(10);
            line.addView(n, nl);
            TextView s = text(15, Color.WHITE, true);
            s.setText(t.score);
            line.addView(s);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, -2);
            lp.topMargin = px(6);
            r.addView(line, lp);
        }
        String status = m.state == Feed.UPCOMING ? "" : m.status;   // an upcoming match has its time in the badge
        if (!status.isEmpty()) {
            TextView s = text(12, m.state == Feed.LIVE ? AMBER : 0x99FFFFFF, false);
            s.setText(status);
            LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, -2);
            sp.topMargin = px(6);
            r.addView(s, sp);
        }
        return r;
    }

    private void paintFocus() {
        for (int i = 0; i < rows.size(); i++) {
            boolean f = i == focus;
            rows.get(i).setBackground(f ? box(0x33FFFFFF, 0xCCFFFFFF, px(14), px(2))
                    : box(0x14FFFFFF, 0, px(14), 0));
        }
    }

    private TextView badge(Feed.Match m, long now, float sp) {
        TextView b = text(sp, Color.WHITE, true);
        b.setLetterSpacing(0.1f);
        b.setPadding(px(8), px(4), px(8), px(4));
        int c;
        if (m.state == Feed.LIVE) { b.setText(m.paused ? "BREAK" : "LIVE"); c = m.paused ? 0x66FFFFFF : LIVE_RED; }
        else if (m.state == Feed.UPCOMING) {
            String at = m.start > 0 ? time(m.start).toUpperCase() : "";
            b.setText(sameDay(m.start, now) ? (at.isEmpty() ? "TODAY" : at) : sameDay(m.start, now + 24 * 3600_000L) ? "TOMORROW" : "NEXT");
            c = 0x33FFFFFF;
        }
        else { b.setText("RESULT"); c = 0x33FFFFFF; }
        b.setBackground(box(c, 0, px(5), 0));
        return b;
    }

    /** Bundled flag for a national side, else a circle with the team's first letter. */
    private View flag(Feed.Team t, float letterSp) {
        int res = flagRes(t.flag);
        if (res != 0) {
            ImageView iv = new ImageView(this);
            iv.setImageResource(res);
            iv.setScaleType(ImageView.ScaleType.FIT_CENTER);
            return iv;
        }
        TextView tv = text(letterSp, Color.WHITE, true);
        tv.setGravity(Gravity.CENTER);
        tv.setText(t.code.isEmpty() ? "?" : t.code.substring(0, 1));
        GradientDrawable g = new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setColor(0x33FFFFFF);
        tv.setBackground(g);
        return tv;
    }

    /** Bundled flat flags (public domain, from flagcdn.com), named here so the resource shrinker keeps them. */
    private static int flagRes(String code) {
        switch (code) {
            case "in": return R.drawable.flag_in;
            case "pk": return R.drawable.flag_pk;
            case "au": return R.drawable.flag_au;
            case "gb_eng": return R.drawable.flag_gb_eng;
            case "nz": return R.drawable.flag_nz;
            case "za": return R.drawable.flag_za;
            case "lk": return R.drawable.flag_lk;
            case "bd": return R.drawable.flag_bd;
            case "af": return R.drawable.flag_af;
            case "zw": return R.drawable.flag_zw;
            case "ie": return R.drawable.flag_ie;
            case "nl": return R.drawable.flag_nl;
            case "gb_sct": return R.drawable.flag_gb_sct;
            case "np": return R.drawable.flag_np;
            case "ae": return R.drawable.flag_ae;
            case "us": return R.drawable.flag_us;
            case "om": return R.drawable.flag_om;
            case "na": return R.drawable.flag_na;
            case "ca": return R.drawable.flag_ca;
            default: return 0;
        }
    }

    private String startsLine(Feed.Match m, long now) {
        if (m.start <= 0) return "";
        String day = sameDay(m.start, now) ? "today" : sameDay(m.start, now + 24 * 3600_000L) ? "tomorrow" : "";
        String type = m.type();
        return "Starts " + (day.isEmpty() ? "" : day + " ") + "at " + time(m.start) + (type.isEmpty() ? "" : "   ·   " + type);
    }

    private String teams() { return getString(R.string.follow_teams).trim().replaceAll("\\s*,\\s*", " or "); }

    private String time(long t) {
        return DateFormat.format(DateFormat.is24HourFormat(this) ? "HH:mm" : "h:mm a", t).toString();
    }

    private static boolean sameDay(long a, long b) {
        Calendar x = Calendar.getInstance(), y = Calendar.getInstance();
        x.setTimeInMillis(a);
        y.setTimeInMillis(b);
        return x.get(Calendar.YEAR) == y.get(Calendar.YEAR) && x.get(Calendar.DAY_OF_YEAR) == y.get(Calendar.DAY_OF_YEAR);
    }
}
