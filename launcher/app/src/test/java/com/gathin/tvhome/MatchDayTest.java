package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.Test;

public class MatchDayTest {

    private static MatchDay.Match match(String home, String away, String start) {
        return new MatchDay.Match("Palmeiras", home, away, "Campeonato Brasileiro", Instant.parse(start),
                Collections.singletonList(new MatchDay.Watch("Premiere", "com.globo.globotv", "")));
    }

    private static final MatchDay.Match BRAGANTINO = match("Palmeiras", "Bragantino", "2026-10-24T21:30:00Z");
    private static final MatchDay.Match SANTOS = match("Santos", "Palmeiras", "2026-10-28T22:30:00Z");
    private static final MatchDay.Match VASCO = match("Palmeiras", "Vasco", "2026-11-01T19:00:00Z");
    private static final MatchDay.Match REMO = match("Palmeiras", "Remo", "2026-11-05T22:30:00Z");
    // Out of order on purpose: sorted() puts them by kick-off.
    private static final List<MatchDay.Match> CALENDAR = MatchDay.sorted(Arrays.asList(VASCO, SANTOS, REMO, BRAGANTINO));

    @Test
    public void nothingWhenTheNextMatchIsMoreThanAWeekAway() {
        assertNull(MatchDay.at(CALENDAR, Instant.parse("2026-10-17T21:29:00Z")));
    }

    @Test
    public void showsTheNextMatchAWeekAhead() {
        MatchDay.State s = MatchDay.at(CALENDAR, Instant.parse("2026-10-17T21:30:00Z"));
        assertSame(BRAGANTINO, s.match);
        assertFalse(s.live);
        assertEquals(Duration.ofDays(7), s.untilStart);
        assertEquals(Arrays.asList(SANTOS, VASCO), s.later);
    }

    @Test
    public void liveFromKickOffForTwoHours() {
        MatchDay.State s = MatchDay.at(CALENDAR, Instant.parse("2026-10-24T23:29:00Z"));
        assertSame(BRAGANTINO, s.match);
        assertTrue(s.live);
        assertEquals(Duration.ZERO, s.untilStart);
    }

    @Test
    public void movesOnWhenTheMatchIsOver() {
        MatchDay.State s = MatchDay.at(CALENDAR, Instant.parse("2026-10-24T23:30:00Z"));
        assertSame(SANTOS, s.match);
        assertEquals(Arrays.asList(VASCO, REMO), s.later);
    }

    @Test
    public void nothingAfterTheLastMatch() {
        assertNull(MatchDay.at(CALENDAR, Instant.parse("2026-11-06T00:30:00Z")));
        assertNull(MatchDay.at(Collections.emptyList(), Instant.parse("2026-11-06T00:30:00Z")));
    }

    @Test
    public void watchTakesAnOptionalLink() {
        MatchDay.Watch plain = MatchDay.Watch.of("Premiere", "com.globo.globotv");
        assertEquals("com.globo.globotv", plain.pkg);
        assertEquals("", plain.uri);
        MatchDay.Watch linked = MatchDay.Watch.of("CazéTV", "com.google.android.youtube.tv|https://www.youtube.com/channel/UCZiYbVptd3PVPf4f6eR6UaQ");
        assertEquals("com.google.android.youtube.tv", linked.pkg);
        assertEquals("https://www.youtube.com/channel/UCZiYbVptd3PVPf4f6eR6UaQ", linked.uri);
    }

    @Test
    public void titleIsHomeThenAway() {
        assertEquals("Santos x Palmeiras", SANTOS.title());
    }
}
