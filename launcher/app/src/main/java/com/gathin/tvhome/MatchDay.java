package com.gathin.tvhome;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Match panel. A theme's config.json can carry the next football matches of a few teams
 * ({@code match_calendar}, written by tools/match_calendar.py); from a few days before a match until it is
 * over, Home shows it under the clock with a button per app that shows it. Scheduling uses the clock against
 * owner-pushed data and is tested on the JVM.
 */
final class MatchDay {

    /** The panel shows a match from this long before kick-off... */
    static final Duration LEAD = Duration.ofDays(7);
    /** ...until this long after: ninety minutes, half time and stoppage time. */
    static final Duration LIVE = Duration.ofHours(2);
    /** How many of the matches after the shown one fit on its "then" line. */
    static final int LATER = 2;
    /** The package that stands for the TV's own Live TV app (config.xml live_tv_package), for free-to-air channels. */
    static final String LIVE_TV = "live_tv";

    static final class Watch {
        /** The broadcasters on the button: "sportv / Premiere". */
        final String name;
        /** The app that shows them, or {@link #LIVE_TV}; empty for a broadcaster match_apps does not map. */
        final String pkg;
        /** A link the app opens instead of its home screen, such as a YouTube channel; empty for none. */
        final String uri;

        Watch(String name, String pkg, String uri) {
            this.name = name;
            this.pkg = pkg;
            this.uri = uri;
        }

        /** match_apps' value: "package" or "package|link". */
        static Watch of(String name, String app) {
            int bar = app.indexOf('|');
            return bar < 0 ? new Watch(name, app.trim(), "")
                    : new Watch(name, app.substring(0, bar).trim(), app.substring(bar + 1).trim());
        }
    }

    static final class Match {
        /** The followed team, for the panel's top line: "Palmeiras". */
        final String team;
        final String home;
        final String away;
        final String competition;
        final Instant start;
        final List<Watch> watch;

        Match(String team, String home, String away, String competition, Instant start, List<Watch> watch) {
            this.team = team;
            this.home = home;
            this.away = away;
            this.competition = competition;
            this.start = start;
            this.watch = Collections.unmodifiableList(new ArrayList<>(watch));
        }

        /** "Palmeiras x Bragantino". */
        String title() {
            return home + " x " + away;
        }
    }

    /** What the panel shows at one moment. */
    static final class State {
        final Match match;
        final boolean live;
        /** Time until kick-off; zero once it is live. */
        final Duration untilStart;
        /** Up to {@link #LATER} matches after {@link #match}, whenever they are. */
        final List<Match> later;

        State(Match match, boolean live, Duration untilStart, List<Match> later) {
            this.match = match;
            this.live = live;
            this.untilStart = untilStart;
            this.later = Collections.unmodifiableList(later);
        }
    }

    private MatchDay() {
    }

    /** Sorted by kick-off, so a calendar edited by hand still shows the right match. */
    static List<Match> sorted(List<Match> calendar) {
        List<Match> out = new ArrayList<>(calendar);
        out.sort((a, b) -> a.start.compareTo(b.start));
        return out;
    }

    /** The match to show at {@code now} in a calendar sorted by kick-off, or null when none is near. */
    static State at(List<Match> calendar, Instant now) {
        for (int i = 0; i < calendar.size(); i++) {
            Match m = calendar.get(i);
            if (!now.isBefore(m.start.plus(LIVE))) continue;          // over
            if (now.isBefore(m.start.minus(LEAD))) return null;       // the next one is still far off
            boolean live = !now.isBefore(m.start);
            List<Match> later = new ArrayList<>(calendar.subList(i + 1, Math.min(calendar.size(), i + 1 + LATER)));
            return new State(m, live, live ? Duration.ZERO : Duration.between(now, m.start), later);
        }
        return null;
    }
}
