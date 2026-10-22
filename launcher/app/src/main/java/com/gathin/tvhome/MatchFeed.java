package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Fetches the match calendar for the match panel (config.xml match_teams) from ge.globo.com, on one background
 * thread, at most every {@link #REFRESH} and only while Home asks for it: no service, no alarm. The last calendar
 * is kept in preferences, so Home shows it at once and while the TV is offline. With match_teams empty nothing
 * here runs.
 */
final class MatchFeed {

    interface Listener {
        void onCalendar(List<MatchDay.Match> calendar);
    }

    static final Duration REFRESH = Duration.ofHours(6);
    static final Duration RETRY = Duration.ofMinutes(30);
    /** How far ahead the calendar reaches. */
    static final Duration AHEAD = Duration.ofDays(45);
    /** ge's agenda pages are about 1 MB; anything far larger is not one. */
    private static final int MAX_BYTES = 6 * 1024 * 1024;

    private static final class Team {
        final int id;
        final String name;
        final String url;

        Team(int id, String name, String url) {
            this.id = id;
            this.name = name;
            this.url = url;
        }
    }

    private final List<Team> teams = new ArrayList<>();
    private final Map<String, String> apps = new HashMap<>();
    private final SharedPreferences prefs;
    private final Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private ExecutorService worker;
    private boolean running;

    MatchFeed(Context context, Listener listener) {
        this.listener = listener;
        prefs = context.getSharedPreferences("matches", Context.MODE_PRIVATE);
        Resources res = context.getResources();
        // "275|Palmeiras|https://ge.globo.com/futebol/times/palmeiras/agenda-de-jogos-do-palmeiras/"
        for (String line : res.getStringArray(R.array.match_teams)) {
            String[] p = line.split("\\|", 3);
            if (p.length < 3) continue;
            try {
                teams.add(new Team(Integer.parseInt(p[0].trim()), p[1].trim(), p[2].trim()));
            } catch (NumberFormatException ignored) {
                // a line without a ge team id is skipped
            }
        }
        // "sportv=com.globo.globotv", or with a link the app opens: "CazéTV=com.google.android.youtube.tv|https://..."
        for (String line : res.getStringArray(R.array.match_apps)) {
            int eq = line.indexOf('=');
            if (eq > 0) apps.put(line.substring(0, eq).trim().toLowerCase(Locale.ROOT), line.substring(eq + 1).trim());
        }
        // The cache holds the apps as match_apps mapped them; a new build may map them differently, so fetch again.
        try {
            long installed = context.getPackageManager().getPackageInfo(context.getPackageName(), 0).lastUpdateTime;
            if (prefs.getLong("installed", 0) != installed) {
                prefs.edit().putLong("installed", installed).remove("fetched").remove("failed").apply();
            }
        } catch (android.content.pm.PackageManager.NameNotFoundException ignored) {
            // our own package is always there
        }
    }

    boolean enabled() {
        return !teams.isEmpty();
    }

    /** The last calendar fetched, sorted by kick-off; empty before the first fetch. */
    List<MatchDay.Match> cached() {
        return MatchDay.sorted(GeAgenda.fromJson(prefs.getString("calendar", "")));
    }

    /** Starts a fetch when the last one is older than {@link #REFRESH}, or the last failure older than {@link #RETRY}. */
    void refreshIfDue() {
        if (!enabled() || running) return;
        long now = System.currentTimeMillis();
        long fetched = prefs.getLong("fetched", 0), failed = prefs.getLong("failed", 0);
        if (now - fetched < REFRESH.toMillis() && fetched <= now) return;
        if (now - failed < RETRY.toMillis() && failed <= now) return;
        running = true;
        if (worker == null) worker = Executors.newSingleThreadExecutor();
        worker.execute(this::fetch);
    }

    void close() {
        if (worker != null) worker.shutdownNow();
        worker = null;
    }

    private void fetch() {
        Instant now = Instant.now();
        List<MatchDay.Match> calendar = new ArrayList<>();
        boolean ok = true;
        for (Team team : teams) {
            try {
                String html = get(team.url);
                // A page without match data means ge changed it: keep the last calendar rather than an empty one.
                if (!html.contains("\"SoccerEvent\"")) {
                    ok = false;
                    break;
                }
                for (MatchDay.Match m : GeAgenda.matches(html, team.id, team.name, apps,
                        now.minus(MatchDay.LIVE), now.plus(AHEAD))) {
                    if (!sameMatch(calendar, m)) calendar.add(m);
                }
            } catch (IOException | RuntimeException e) {
                ok = false;
                break;
            }
        }
        final List<MatchDay.Match> sorted = MatchDay.sorted(calendar);
        final boolean success = ok;
        main.post(() -> {
            running = false;
            long t = System.currentTimeMillis();
            if (!success) {
                prefs.edit().putLong("failed", t).apply();
                return;
            }
            prefs.edit().putString("calendar", GeAgenda.toJson(sorted)).putLong("fetched", t).apply();
            listener.onCalendar(sorted);
        });
    }

    /** Two followed teams can meet; the match is listed once. */
    private static boolean sameMatch(List<MatchDay.Match> list, MatchDay.Match m) {
        for (MatchDay.Match o : list) {
            if (o.start.equals(m.start) && o.home.equals(m.home) && o.away.equals(m.away)) return true;
        }
        return false;
    }

    private static String get(String url) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        c.setRequestProperty("User-Agent", "cleanyourtv");
        try (InputStream in = c.getInputStream()) {
            if (c.getResponseCode() != 200) throw new IOException("HTTP " + c.getResponseCode());
            ByteArrayOutputStream out = new ByteArrayOutputStream(1 << 20);
            byte[] buf = new byte[16384];
            for (int n; (n = in.read(buf)) > 0; ) {
                out.write(buf, 0, n);
                if (out.size() > MAX_BYTES) throw new IOException("page too large");
            }
            return new String(out.toByteArray(), StandardCharsets.UTF_8);
        } finally {
            c.disconnect();
        }
    }
}
