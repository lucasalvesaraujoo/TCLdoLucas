package com.gathin.tvhome;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Reads a team's agenda page on ge.globo.com ("agenda-de-jogos-do-palmeiras"). ge has no documented API, but
 * the page carries each match as a JSON object, {"__typename":"SoccerEvent", "match": {...}}, with its
 * "onde assistir" list (liveWatchSources). Pure Java, tested on the JVM against a saved page.
 */
final class GeAgenda {

    private static final String MARKER = "{\"__typename\":\"SoccerEvent\"";
    /** ge's times are Brasília time. */
    static final ZoneId BRASILIA = ZoneId.of("America/Sao_Paulo");
    /** Under-23, Olympic and other side competitions can share the first team's id. */
    private static final Pattern SKIP_COMPETITION =
            Pattern.compile("sub-?\\s?\\d+|ol[ií]mpic|feminin|\\bold\\b", Pattern.CASE_INSENSITIVE);
    /** Entries on ge's "onde assistir" list that show no match: the fantasy game and ticket shops. */
    private static final Pattern NOT_A_BROADCASTER = Pattern.compile("cartola|ingresso", Pattern.CASE_INSENSITIVE);

    private GeAgenda() {
    }

    /**
     * The first team's matches on {@code html} that kick off between {@code from} and {@code until}.
     * {@code apps} maps a broadcaster as ge names it, in lower case ("sportv"), to the app that shows it; a
     * broadcaster it does not know is kept with no app, so every option still shows. The fantasy game and ticket
     * shops are left out.
     */
    static List<MatchDay.Match> matches(String html, int teamId, String teamName, Map<String, String> apps,
                                        Instant from, Instant until) {
        List<MatchDay.Match> out = new ArrayList<>();
        for (int at = html.indexOf(MARKER); at >= 0; at = html.indexOf(MARKER, at + 1)) {
            int end = objectEnd(html, at);
            if (end < 0) break;
            try {
                JSONObject match = new JSONObject(html.substring(at, end)).optJSONObject("match");
                MatchDay.Match m = match == null ? null : match(match, teamId, teamName, apps);
                if (m != null && !m.start.isBefore(from) && !m.start.isAfter(until) && !contains(out, m)) out.add(m);
            } catch (JSONException | DateTimeParseException ignored) {
                // one unreadable match leaves the rest of the page usable
            }
        }
        return out;
    }

    private static MatchDay.Match match(JSONObject match, int teamId, String teamName, Map<String, String> apps) {
        JSONObject home = match.optJSONObject("firstContestant"), away = match.optJSONObject("secondContestant");
        if (home == null || away == null) return null;
        if (home.optInt("id") != teamId && away.optInt("id") != teamId) return null;
        String competition = competition(match);
        if (SKIP_COMPETITION.matcher(competition).find()) return null;
        String date = match.optString("startDate", ""), hour = match.optString("startHour", "");
        // A match without a time yet cannot be counted down; it comes in on a later fetch.
        if (date.isEmpty() || hour.isEmpty() || hour.equals("null")) return null;
        Instant start = LocalDate.parse(date).atTime(LocalTime.parse(hour)).atZone(BRASILIA).toInstant();
        return new MatchDay.Match(teamName, name(home), name(away), competition, start,
                watch(match.optJSONArray("liveWatchSources"), apps));
    }

    private static String competition(JSONObject match) {
        JSONObject phase = match.optJSONObject("phase");
        JSONObject edition = phase == null ? null : phase.optJSONObject("championshipEdition");
        JSONObject championship = edition == null ? null : edition.optJSONObject("championship");
        return championship == null ? "" : championship.optString("name", "");
    }

    private static String name(JSONObject team) {
        String popular = team.optString("popularName", "");
        return popular.isEmpty() ? team.optString("name", "") : popular;
    }

    /** One button per app and link, in ge's order: sportv and Premiere both open Globoplay, as "sportv / Premiere". */
    static List<MatchDay.Watch> watch(JSONArray sources, Map<String, String> apps) {
        Map<String, List<String>> byApp = new LinkedHashMap<>();
        for (int i = 0; sources != null && i < sources.length(); i++) {
            JSONObject s = sources.optJSONObject(i);
            String name = s == null ? "" : s.optString("name", "").trim();
            if (name.isEmpty() || NOT_A_BROADCASTER.matcher(name).find()) continue;
            // A broadcaster with no app gets a key of its own, so two unknown ones do not merge.
            String pkg = apps.getOrDefault(name.toLowerCase(Locale.ROOT), "?" + name);
            List<String> names = byApp.computeIfAbsent(pkg, k -> new ArrayList<>());
            if (!names.contains(name)) names.add(name);
        }
        List<MatchDay.Watch> out = new ArrayList<>();
        for (Map.Entry<String, List<String>> e : byApp.entrySet()) {
            List<String> names = new ArrayList<>(e.getValue());
            // "globoplay" says nothing next to sportv or Premiere, which are shown in it anyway.
            if (names.size() > 1) names.removeIf(n -> n.equalsIgnoreCase("globoplay"));
            String name = String.join(" / ", names);
            out.add(e.getKey().startsWith("?") ? new MatchDay.Watch(name, "", "") : MatchDay.Watch.of(name, e.getKey()));
        }
        return out;
    }

    private static boolean contains(List<MatchDay.Match> list, MatchDay.Match m) {
        for (MatchDay.Match o : list) {
            if (o.start.equals(m.start) && o.home.equals(m.home) && o.away.equals(m.away)) return true;
        }
        return false;
    }

    /** The index just past the JSON object that opens at {@code start}, or -1 when the page ends first. */
    static int objectEnd(String s, int start) {
        int depth = 0;
        boolean inString = false;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (inString) {
                if (c == '\\') i++;
                else if (c == '"') inString = false;
            } else if (c == '"') {
                inString = true;
            } else if (c == '{' || c == '[') {
                depth++;
            } else if (c == '}' || c == ']') {
                if (--depth == 0) return i + 1;
            }
        }
        return -1;
    }

    // ---- the cache: the last fetched calendar, so Home shows it at once and while offline ----

    static String toJson(List<MatchDay.Match> calendar) {
        JSONArray list = new JSONArray();
        try {
            for (MatchDay.Match m : calendar) {
                JSONArray watch = new JSONArray();
                for (MatchDay.Watch w : m.watch) watch.put(new JSONObject().put("name", w.name).put("package", w.pkg).put("uri", w.uri));
                list.put(new JSONObject().put("team", m.team).put("home", m.home).put("away", m.away)
                        .put("competition", m.competition).put("start", m.start.toString()).put("watch", watch));
            }
        } catch (JSONException ignored) {
            // put() only throws for non-finite numbers
        }
        return list.toString();
    }

    static List<MatchDay.Match> fromJson(String json) {
        List<MatchDay.Match> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray list = new JSONArray(json);
            for (int i = 0; i < list.length(); i++) {
                JSONObject m = list.getJSONObject(i);
                List<MatchDay.Watch> watch = new ArrayList<>();
                JSONArray w = m.optJSONArray("watch");
                for (int j = 0; w != null && j < w.length(); j++) {
                    JSONObject o = w.getJSONObject(j);
                    watch.add(new MatchDay.Watch(o.optString("name", ""), o.optString("package", ""), o.optString("uri", "")));
                }
                out.add(new MatchDay.Match(m.optString("team", ""), m.optString("home", ""), m.optString("away", ""),
                        m.optString("competition", ""), Instant.parse(m.getString("start")), watch));
            }
        } catch (JSONException | DateTimeParseException e) {
            out.clear();   // an unreadable cache is as good as none: the next fetch rewrites it
        }
        return out;
    }
}
