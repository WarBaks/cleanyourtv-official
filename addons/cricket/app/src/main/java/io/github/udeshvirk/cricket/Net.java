package io.github.udeshvirk.cricket;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.zip.GZIPInputStream;

/** One GET of a JSON document, gzip on (the scoreboard is ~9 KB that way, a match summary ~22 KB). */
final class Net {
    /** ESPN answered 429 or 503: stay away for retryMs. */
    static final class Busy extends IOException {
        final long retryMs;
        Busy(long retryMs) { super("busy"); this.retryMs = retryMs; }
    }

    static final long MIN_PAUSE = 30 * 60_000L, MAX_PAUSE = 6 * 3600_000L;

    static JSONObject get(String url) throws Exception {
        HttpURLConnection con = (HttpURLConnection) new URL(url).openConnection();
        try {
            con.setConnectTimeout(8000);
            con.setReadTimeout(15000);
            con.setRequestProperty("Accept", "application/json");
            con.setRequestProperty("Accept-Encoding", "gzip");   // explicit, so it is unzipped below
            int code = con.getResponseCode();
            if (code == 429 || code == 503) {
                long after = 0;
                try { after = Long.parseLong(con.getHeaderField("Retry-After").trim()) * 1000L; } catch (Exception ignored) { }
                throw new Busy(Math.max(MIN_PAUSE, Math.min(after, MAX_PAUSE)));
            }
            if (code != 200) throw new IOException("HTTP " + code);
            InputStream in = con.getInputStream();
            if ("gzip".equalsIgnoreCase(con.getContentEncoding())) in = new GZIPInputStream(in);
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[16384];
            for (int r; (r = in.read(buf)) > 0; ) {
                bo.write(buf, 0, r);
                if (bo.size() > 4 * 1024 * 1024) throw new IOException("response too large");
            }
            return new JSONObject(bo.toString("UTF-8"));
        } finally {
            con.disconnect();
        }
    }

    private Net() {}
}
