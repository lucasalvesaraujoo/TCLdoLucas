package com.gathin.tvhome;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class GeAgendaTest {

    private static final Map<String, String> APPS = new HashMap<>();

    static {
        APPS.put("globo", "live_tv");
        APPS.put("sportv", "com.globo.globotv");
        APPS.put("premiere", "com.globo.globotv");
        APPS.put("globoplay", "com.globo.globotv");
        APPS.put("prime vídeo", "com.amazon.amazonvideo.livingroom");
        APPS.put("ge tv", "com.google.android.youtube.tv|https://www.youtube.com/channel/UCgCKagVhzGnZcuP9bSMgMCg");
    }

    private static String event(int homeId, String home, int awayId, String away, String competition,
                                String date, String hour, String sources) {
        return "{\"__typename\":\"SoccerEvent\",\"editorialData\":{\"url\":\"\"},\"match\":{"
                + "\"firstContestant\":{\"__typename\":\"Team\",\"id\":" + homeId + ",\"name\":\"" + home + " FC\",\"popularName\":\"" + home + "\"},"
                + "\"liveWatchSources\":[" + sources + "],"
                + "\"phase\":{\"championshipEdition\":{\"championship\":{\"name\":\"" + competition + "\"}},\"name\":\"Fase \\u00fanica\"},"
                + "\"secondContestant\":{\"__typename\":\"Team\",\"id\":" + awayId + ",\"name\":\"" + away + " FC\",\"popularName\":\"" + away + "\"},"
                + "\"startDate\":\"" + date + "\",\"startHour\":" + (hour == null ? "null" : "\"" + hour + "\"") + ","
                + "\"transmission\":{\"url\":\"https:\\/\\/ge.globo.com\\/jogo\\/x.ghtml\"}}}";
    }

    private static String source(String name) {
        return "{\"cta\":\"\",\"description\":\"{not a brace}\",\"name\":\"" + name + "\",\"url\":\"\"}";
    }

    // A page as ge builds it: matches inside other JSON, between HTML. Palmeiras' men's team is 275, the women's 4776.
    private static final String PAGE = "<html><script>window.data = {\"agenda\":[{\"future\":["
            + event(275, "Palmeiras", 1, "Vasco", "Copa do Brasil", "2026-11-01", "16:00:00",
                    source("sportv") + "," + source("Premiere") + "," + source("Globo") + "," + source("globoplay")
                            + "," + source("Prime Vídeo") + "," + source("Ge TV") + "," + source("Cartola") + "," + source("Nosso Futebol"))
            + "," + event(4776, "Palmeiras", 2, "Santa Fe", "Taça Libertadores Feminina", "2026-10-24", "18:00:00", source("sportv"))
            + "," + event(2, "Santos", 275, "Palmeiras", "Campeonato Brasileiro", "2026-10-28", "19:30:00", source("Premiere"))
            + "," + event(3, "Cruzeiro", 275, "Palmeiras", "Campeonato Brasileiro", "2026-11-18", null, "")
            + "," + event(275, "Palmeiras", 4, "Bahia", "Campeonato Brasileiro", "2025-09-28", "16:00:00", source("Premiere"))
            + "," + event(275, "Palmeiras", 5, "Brasil Sub-20", "Copa do Brasil Sub-20", "2026-10-29", "15:00:00", "")
            + "]}]};</script></html>";

    private static List<MatchDay.Match> parse() {
        return GeAgenda.matches(PAGE, 275, "Palmeiras", APPS,
                Instant.parse("2026-10-22T00:00:00Z"), Instant.parse("2026-12-06T00:00:00Z"));
    }

    @Test
    public void keepsOnlyTheFirstTeamsTimedMatchesInTheWindow() {
        List<MatchDay.Match> m = parse();
        assertEquals(2, m.size());
        assertEquals("Palmeiras x Vasco", m.get(0).title());
        assertEquals("Santos x Palmeiras", m.get(1).title());
        assertEquals("Palmeiras", m.get(0).team);
        assertEquals("Copa do Brasil", m.get(0).competition);
    }

    @Test
    public void startIsBrasiliaTime() {
        assertEquals(Instant.parse("2026-11-01T19:00:00Z"), parse().get(0).start);
    }

    @Test
    public void oneButtonPerAppAndUnknownBroadcastersKeptWithoutAnApp() {
        List<MatchDay.Watch> w = parse().get(0).watch;
        assertEquals(5, w.size());
        assertEquals("sportv / Premiere", w.get(0).name);
        assertEquals("com.globo.globotv", w.get(0).pkg);
        assertEquals("Globo", w.get(1).name);
        assertEquals("live_tv", w.get(1).pkg);
        assertEquals("Prime Vídeo", w.get(2).name);
        assertEquals("", w.get(2).uri);
        // A link after the package: Ge TV opens its YouTube channel.
        assertEquals("Ge TV", w.get(3).name);
        assertEquals("com.google.android.youtube.tv", w.get(3).pkg);
        assertEquals("https://www.youtube.com/channel/UCgCKagVhzGnZcuP9bSMgMCg", w.get(3).uri);
        // Cartola is left out; a broadcaster match_apps does not know stays, with no app to open.
        assertEquals("Nosso Futebol", w.get(4).name);
        assertEquals("", w.get(4).pkg);
    }

    @Test
    public void cacheRoundTrips() {
        List<MatchDay.Match> m = parse();
        List<MatchDay.Match> back = GeAgenda.fromJson(GeAgenda.toJson(m));
        assertEquals(m.size(), back.size());
        assertEquals(m.get(0).start, back.get(0).start);
        assertEquals(m.get(0).title(), back.get(0).title());
        assertEquals("sportv / Premiere", back.get(0).watch.get(0).name);
        assertEquals(m.get(0).watch.get(3).uri, back.get(0).watch.get(3).uri);
        assertTrue(GeAgenda.fromJson("not json").isEmpty());
        assertTrue(GeAgenda.fromJson("").isEmpty());
    }

    @Test
    public void objectEndSkipsBracesInStrings() {
        String s = "x{\"a\":\"}\\\"{\",\"b\":[1,{\"c\":2}]}y";
        assertEquals(s.length() - 1, GeAgenda.objectEnd(s, 1));
        assertEquals(-1, GeAgenda.objectEnd("{\"a\":1", 0));
    }
}
