/*
 * Ported from SpotifyPlus (https://github.com/Xposed-Modules-Repo/com.lenerd46.spotifyplus), app/src/main/java/com/lenerd46/spotifyplus/lyrics/LyricsProviders.java.
 *
 * MIT License
 *
 * Copyright (c) 2025 Devon Shoutz
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */
package app.liquidspot.xposed.features.player.lyrics.providers;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import java.io.StringReader;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import org.xml.sax.InputSource;

public final class LyricsProviders {
    private LyricsProviders() {}

    public interface Config {
        List<Provider> order();
        boolean isEnabled(Provider p);
        String paxSenixKey();
    }

    private static void log(Throwable error) {
        app.liquidspot.xposed.util.Logger.INSTANCE.w("Letras: erro", error);
    }

    private static void log(String message) {
        app.liquidspot.xposed.util.Logger.INSTANCE.d("Letras: " + message);
    }

    private static boolean isStaticResult(Result r) {
        return r.isUntimed() || r.lines.stream().noneMatch(l -> l.start > 0 || l.end > 0);
    }

    public enum Provider {
        LRC_RED("lrc.red", "Apple timings by recording, syllable by syllable", true, false),
        BINI_LYRICS("BiniLyrics", "The same Apple timings, matched on the recording itself", true, false),
        BETTER_LYRICS("BetterLyrics", "Apple Music timings, word by word", true, false),
        BETTER_LYRICS_PORTATO("BetterLyrics Portato", "QQ Music karaoke timings through BetterLyrics", true, false),
        PAXSENIX("PaxSenix", "Apple Music timings through PaxSenix; API key required", true, true),
        PAXSENIX_SPOTIFY("PaxSenix: Spotify", "Spotify lyrics via PaxSenix; API key required", false, true),
        PAXSENIX_MUSIXMATCH("PaxSenix: Musixmatch", "Musixmatch timings via PaxSenix; API key required", true, true),
        LYRICS_PLUS("LyricsPlus", "Syllable by syllable, on community mirrors", true, false),
        UNISON("Unison", "Contributed by listeners, so it has what nobody licensed", true, false),
        KUGOU("KuGou", "Whole lines, strong outside the English catalogue", false, false),
        LRCLIB("LRCLIB", "Whole lines only, and always up", false, false),
        MUSIXMATCH("Musixmatch", "Whole lines, from the biggest lyrics database there is", false, false),
        GENIUS("Genius", "Plain text fallback, massive web catalogue", false, false);

        public final String label;
        public final String detail;
        public final boolean wordSynced;
        public final boolean requiresKey;

        Provider(String label, String detail, boolean wordSynced, boolean requiresKey) {
            this.label = label;
            this.detail = detail;
            this.wordSynced = wordSynced;
            this.requiresKey = requiresKey;
        }

        public String prefKey() { return "lyrics_provider_" + name(); }
    }

    public static final class TimedWord {
        public final String text; public final double start; public final double end; public final boolean partOfWord;
        public TimedWord(String text, double start, double end, boolean partOfWord) {
            this.text = text; this.start = start; this.end = end; this.partOfWord = partOfWord;
        }
    }

    public static final class TimedLine {
        public final double start; public final double end; public final String text;
        public final List<TimedWord> words; public final boolean syllable;
        public final boolean untimed;
        public final String agent; public final String bgText; public final List<TimedWord> bgWords;
        public TimedLine(double start, double end, String text, List<TimedWord> words, boolean syllable) {
            this(start, end, text, words, syllable, false);
        }
        public TimedLine(double start, double end, String text, List<TimedWord> words, boolean syllable, boolean untimed) {
            this(start, end, text, words, syllable, untimed, null, null, new ArrayList<>());
        }
        public TimedLine(double start, double end, String text, List<TimedWord> words, boolean syllable, boolean untimed,
                         String agent, String bgText, List<TimedWord> bgWords) {
            this.start = start; this.end = end; this.text = text; this.words = words; this.syllable = syllable; this.untimed = untimed;
            this.agent = agent; this.bgText = bgText; this.bgWords = bgWords;
        }
    }

    public static final class Result {
        public final Provider source; public final List<TimedLine> lines; public final boolean syllable;
        public Result(Provider source, List<TimedLine> lines, boolean syllable) {
            this.source = source; this.lines = lines; this.syllable = syllable;
        }
        public boolean isSyllable() { return syllable && lines.stream().anyMatch(l -> !l.words.isEmpty()); }
        public boolean isUntimed() { return !lines.isEmpty() && lines.stream().allMatch(l -> l.untimed); }
    }

    private static final String DEFAULT_USER_AGENT = "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36 LiquidSpot/" + app.liquidspot.BuildConfig.VERSION_NAME;
    private static final OkHttpClient CLIENT = new OkHttpClient.Builder().addInterceptor(chain -> { Request orig = chain.request(); Request.Builder req = orig.newBuilder(); if (orig.header("User-Agent") == null) req.header("User-Agent", DEFAULT_USER_AGENT); return chain.proceed(req.build()); })
            .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS).callTimeout(15, TimeUnit.SECONDS).build();
    private static final Gson GSON = new Gson();

    private static String norm(String s) {
        if (s == null) return "";
        return s.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N} ]", "").replaceAll("\\s+", " ").trim();
    }

    public static Result fetch(Config ctx, String title, String artist, String album, long durationMs, boolean syllableOnly, boolean skipUntimed) {
        if (title == null || title.trim().isEmpty()) return null;
        if (artist == null) artist = "";
        log("fetch title=" + title + " artist=" + artist
                + " durationMs=" + durationMs + " syllableOnly=" + syllableOnly + " skipUntimed=" + skipUntimed);
        if (durationMs <= 0) log("Track duration unknown (0): providers will not filter by duration");
        List<Provider> enabled = new ArrayList<>();
        for (Provider p : ctx.order()) {
            if (!ctx.isEnabled(p)) continue;
            if (p.requiresKey && ctx.paxSenixKey().isEmpty()) {
                log("Skipping " + p.name() + ": no PaxSenix API key");
                continue;
            }
            enabled.add(p);
        }
        if (enabled.isEmpty()) return null;
        ExecutorService exec = Executors.newFixedThreadPool(enabled.size(), r -> {
            Thread th = new Thread(r);
            th.setDaemon(true);
            th.setName("LyricsProviders");
            return th;
        });
        final String queryTitle = title;
        final String queryArtist = artist;
        final String queryAlbum = album;
        final long queryDurationMs = durationMs;
        List<Future<Result>> futures = new ArrayList<>();
        for (Provider p : enabled) {
            final Provider provider = p;
            futures.add(exec.submit(() -> {
                try {
                    return fetchOne(ctx, provider, queryTitle, queryArtist, queryAlbum, queryDurationMs);
                } catch (Throwable th) {
                    log("" + provider.name() + " failed: " + th);
                    return null;
                }
            }));
        }
        try {
            for (int i = 0; i < enabled.size(); i++) {
                Provider p = enabled.get(i);
                Result r;
                try {
                    r = futures.get(i).get();
                } catch (Throwable th) {
                    log("" + p.name() + " failed: " + th);
                    continue;
                }
                if (r == null || r.lines.isEmpty()) {
                    log("" + p.name() + " miss (no lines)");
                    continue;
                }
                if (skipUntimed && (r.isUntimed() || isStaticResult(r))) {
                    log("" + p.name() + " returned " + r.lines.size()
                            + " untimed lines, skipped for NPV");
                    continue;
                }
                if (syllableOnly && !r.isSyllable()) {
                    log("" + p.name() + " returned " + r.lines.size()
                            + " line-only lines, continuing for syllable");
                    continue;
                }
                log("" + p.name() + " hit: " + r.lines.size() + " lines"
                        + (r.isSyllable() ? " (syllable) " : r.isUntimed() ? " (static, untimed) " : " (line) "));
                return r;
            }
        } finally {
            for (Future<Result> f : futures) f.cancel(true);
            exec.shutdownNow();
        }
        return null;
    }

    private static Result fetchOne(Config ctx, Provider p, String title, String artist, String album, long durationMs) throws Exception {
        switch (p) {
            case LRC_RED: return lrcRed(title, artist, durationMs);
            case BINI_LYRICS: return bini(title, artist, album, durationMs);
            case BETTER_LYRICS: return better(title, artist, album, durationMs, "https://lyrics-api.boidu.dev/getLyrics");
            case BETTER_LYRICS_PORTATO: return better(title, artist, album, durationMs, "https://lyrics-api.boidu.dev/qq/getLyrics");
            case PAXSENIX: case PAXSENIX_SPOTIFY: case PAXSENIX_MUSIXMATCH: return paxSenix(ctx, title, artist);
            case LYRICS_PLUS: return lyricsPlus(title, artist, album, durationMs);
            case UNISON: return unison(title, artist, album, durationMs);
            case KUGOU: return kugou(title, artist, album, durationMs);
            case LRCLIB: return lrclib(title, artist, durationMs);
            case MUSIXMATCH: return musixmatch(title, artist, durationMs);
            case GENIUS: return genius(title, artist);
            default: return null;
        }
    }

    private static String get(String url) throws Exception {
        return get(url, null, null);
    }
    private static String get(String url, String header, String value) throws Exception {
        Request.Builder b = new Request.Builder().url(url);
        if (header != null) b.header(header, value);
        try (Response r = CLIENT.newCall(b.build()).execute()) {
            if (!r.isSuccessful()) { log("HTTP " + r.code() + " for " + url); return null; }
            if (r.body() == null) return null;
            return r.body().string();
        }
    }
    private static String enc(String s) {
        try { return URLEncoder.encode(s == null ? "" : s, StandardCharsets.UTF_8.name()); } catch (Exception e) { return s; }
    }
    private static String str(JsonObject o, String... keys) {
        if (o == null) return null;
        for (String k : keys) {
            JsonElement e = o.get(k);
            if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString()) return e.getAsString();
        }
        return null;
    }
    private static double num(JsonObject o, String key, double def) {
        try { JsonElement e = o.get(key); return e != null && e.isJsonPrimitive() ? e.getAsDouble() : def; } catch (Exception e) { return def; }
    }
    private static boolean hasText(List<TimedLine> lines) {
        return lines != null && !lines.isEmpty() && lines.stream().anyMatch(l -> l.text != null && !l.text.trim().isEmpty());
    }

    private static final Pattern LRC_STAMP = Pattern.compile("\\[(\\d{1,2}):(\\d{2})(?:\\.(\\d{2,3}))?\\]");
    private static final Pattern WORD_STAMP = Pattern.compile("<(\\d{1,2}):(\\d{2})(?:\\.(\\d{2,3}))?>");
    private static final Pattern QRC_CONTENT = Pattern.compile("LyricContent=\"([\\s\\S]*?)\"\\s*/>");
    private static final Pattern QRC_LINE = Pattern.compile("^\\[(\\d+),(\\d+)\\](.*)$");
    private static final Pattern QRC_WORD = Pattern.compile("([^()]*)\\((\\d+),(\\d+)\\)");

    public static List<TimedLine> parseLrc(String lrc) {
        List<TimedLine> out = new ArrayList<>();
        if (lrc == null) return out;
        for (String raw : lrc.split("\n")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("[") && !LRC_STAMP.matcher(line).find()) continue;
            Matcher m = LRC_STAMP.matcher(line);
            List<double[]> stamps = new ArrayList<>();
            int lastEnd = 0;
            while (m.find()) {
                double t = Integer.parseInt(m.group(1)) * 60.0 + Integer.parseInt(m.group(2))
                        + (m.group(3) == null ? 0 : Integer.parseInt(m.group(3)) / (m.group(3).length() == 2 ? 100.0 : 1000.0));
                stamps.add(new double[]{t}); lastEnd = m.end();
            }
            if (stamps.isEmpty()) continue;
            String body = line.substring(lastEnd).trim();
            Matcher wm = WORD_STAMP.matcher(body);
            List<TimedWord> words = new ArrayList<>();
            List<String> wordTexts = new ArrayList<>(); List<Double> wordTimes = new ArrayList<>();
            while (wm.find()) {
                double t = Integer.parseInt(wm.group(1)) * 60.0 + Integer.parseInt(wm.group(2))
                        + (wm.group(3) == null ? 0 : Integer.parseInt(wm.group(3)) / (wm.group(3).length() == 2 ? 100.0 : 1000.0));
                wordTimes.add(t);
            }
            if (!wordTimes.isEmpty()) {
                String[] parts = WORD_STAMP.split(body);
                for (int i = 1; i < parts.length && i - 1 < wordTimes.size(); i++) {
                    String w = parts[i].trim();
                    if (w.isEmpty()) continue;
                    double s = wordTimes.get(i - 1);
                    double e = i < wordTimes.size() ? wordTimes.get(i) : s + 0.5;
                    words.add(new TimedWord(w, s, e, false));
                }
                if (!words.isEmpty()) {
                    out.add(new TimedLine(words.get(0).start, words.get(words.size() - 1).end,
                            words.stream().map(w -> w.text).reduce((a, b) -> a + " " + b).orElse(""), words, true));
                    continue;
                }
            }
            String text = body.replaceAll("<[^>]*>", "").trim();
            for (double[] s : stamps) out.add(new TimedLine(s[0], s[0], text, new ArrayList<>(), false));
        }
        out.sort((a, b) -> Double.compare(a.start, b.start));
        for (int i = 0; i < out.size(); i++) {
            TimedLine l = out.get(i);
            double end = i + 1 < out.size() ? out.get(i + 1).start : l.start + 3.0;
            if (!l.syllable) out.set(i, new TimedLine(l.start, end, l.text, l.words, false));
        }
        return out;
    }

    public static List<TimedLine> parseQrc(String raw) {
        List<TimedLine> out = new ArrayList<>();
        Matcher content = QRC_CONTENT.matcher(raw);
        if (!content.find()) return out;
        String body = content.group(1).replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&amp;", "&");
        for (String rawLine : body.split("\\r?\\n")) {
            Matcher lm = QRC_LINE.matcher(rawLine.trim());
            if (!lm.matches()) continue;
            double start = Long.parseLong(lm.group(1)) / 1000.0;
            double end = start + Long.parseLong(lm.group(2)) / 1000.0;
            Matcher wm = QRC_WORD.matcher(lm.group(3));
            List<TimedWord> words = new ArrayList<>();
            StringBuilder text = new StringBuilder();
            while (wm.find()) {
                String piece = wm.group(1);
                text.append(piece);
                if (piece.trim().isEmpty()) continue;
                double ws = Long.parseLong(wm.group(2)) / 1000.0;
                double we = ws + Long.parseLong(wm.group(3)) / 1000.0;
                words.add(new TimedWord(piece.trim(), ws, we, false));
            }
            String line = (text.length() > 0 ? text.toString() : lm.group(3)).replaceAll("\\s+", " ").trim();
            if (line.isEmpty()) continue;
            out.add(new TimedLine(start, end, line, words, !words.isEmpty()));
        }
        out.sort((a, b) -> Double.compare(a.start, b.start));
        return out;
    }

    public static List<TimedLine> parseTtml(String ttml) {
        List<TimedLine> out = new ArrayList<>();
        if (ttml == null || !ttml.contains("<p")) return out;
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setNamespaceAware(false);
            try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (Exception ignored) {}
            try { f.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (Exception ignored) {}
            Document doc = f.newDocumentBuilder().parse(new InputSource(new StringReader(ttml)));
            NodeList ps = doc.getElementsByTagName("p");
            for (int i = 0; i < ps.getLength(); i++) {
                Element p = (Element) ps.item(i);
                String role = p.getAttribute("ttm:agent");
                if (role.contains("translation") || role.contains("roman")) continue;
                double pStart = parseTime(p.getAttribute("begin"), -1);
                double pEnd = parseTime(p.getAttribute("end"), -1);
                List<TimedWord> words = new ArrayList<>();
                List<TimedWord> bgWords = new ArrayList<>();
                StringBuilder lineText = new StringBuilder();
                StringBuilder bgText = new StringBuilder();
                NodeList kids = p.getChildNodes();
                for (int k = 0; k < kids.getLength(); k++) {
                    Node kid = kids.item(k);
                    if (kid.getNodeType() == Node.ELEMENT_NODE && "span".equals(kid.getNodeName())
                            && ((Element) kid).getAttribute("ttm:role").contains("x-bg")) {
                        collectBackground((Element) kid, pStart, bgText, bgWords);
                        continue;
                    }
                    if (kid.getNodeType() == Node.ELEMENT_NODE && "span".equals(kid.getNodeName())) {
                        Element span = (Element) kid;
                        String txt = span.getTextContent();
                        if (txt == null || txt.isEmpty()) continue;
                        double s = parseTime(span.getAttribute("begin"), pStart);
                        double e = parseTime(span.getAttribute("end"), s + 0.3);
                        boolean nextIsSpan = k + 1 < kids.getLength() && kids.item(k + 1).getNodeType() == Node.ELEMENT_NODE;
                        words.add(new TimedWord(txt, s, e, nextIsSpan));
                        lineText.append(txt);
                    } else if (kid.getNodeType() == Node.TEXT_NODE) {
                        lineText.append(kid.getNodeValue());
                    } else if (kid.getNodeType() == Node.ELEMENT_NODE && "br".equals(kid.getNodeName())) {
                        lineText.append('\n');
                    }
                }
                String text = lineText.toString().trim();
                if (text.isEmpty()) continue;
                if (pStart < 0) {
                    out.add(new TimedLine(0, 0, text, new ArrayList<>(), false, true));
                    continue;
                }
                boolean syllable = !words.isEmpty();
                String agent = role.isEmpty() ? null : role;
                String bg = bgText.toString().replaceAll("\\s+", " ").trim();
                out.add(new TimedLine(pStart, pEnd > pStart ? pEnd : pStart + 1.0, text, words, syllable, false,
                        agent, bg.isEmpty() ? null : bg, bgWords));
            }
        } catch (Throwable ignored) {}
        out.sort((a, b) -> Double.compare(a.start, b.start));
        return out;
    }

    private static void collectBackground(Element bg, double pStart, StringBuilder text, List<TimedWord> words) {
        NodeList kids = bg.getChildNodes();
        boolean any = false;
        for (int k = 0; k < kids.getLength(); k++) {
            Node kid = kids.item(k);
            if (kid.getNodeType() == Node.ELEMENT_NODE && "span".equals(kid.getNodeName())) {
                Element span = (Element) kid;
                String txt = span.getTextContent();
                if (txt == null || txt.isEmpty()) continue;
                any = true;
                double s = parseTime(span.getAttribute("begin"), pStart);
                double e = parseTime(span.getAttribute("end"), s + 0.3);
                boolean nextIsSpan = k + 1 < kids.getLength() && kids.item(k + 1).getNodeType() == Node.ELEMENT_NODE;
                words.add(new TimedWord(txt, s, e, nextIsSpan));
                text.append(txt);
            } else if (kid.getNodeType() == Node.TEXT_NODE) {
                text.append(kid.getNodeValue());
            }
        }
        if (!any) text.append(bg.getTextContent());
    }

    private static double parseTime(String s, double def) {
        if (s == null || s.isEmpty()) return def;
        try {
            s = s.trim();
            if (s.endsWith("s")) s = s.substring(0, s.length() - 1);
            if (s.contains(":")) {
                String[] parts = s.split(":");
                double v = 0;
                for (String part : parts) v = v * 60 + Double.parseDouble(part);
                return v;
            }
            return Double.parseDouble(s);
        } catch (Exception e) { return def; }
    }

    public static List<TimedLine> parseGeneric(String raw) {
        if (raw == null) return new ArrayList<>();
        String body = raw.trim();
        if (body.startsWith("{") || body.startsWith("[")) {
            try {
                JsonElement el = GSON.fromJson(body, JsonElement.class);
                String extracted = extractLyricsString(el);
                if (extracted != null && !extracted.trim().isEmpty()) body = extracted.trim();
            } catch (Exception ignored) {}
        }
        if (body.contains("LyricContent=")) return parseQrc(body);
        body = body.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&amp;", "&");
        List<TimedLine> ttml = parseTtml(body);
        if (hasText(ttml)) return ttml;
        List<TimedLine> lrc = parseLrc(body);
        if (hasText(lrc)) return lrc;
        List<TimedLine> plain = new ArrayList<>();
        for (String line : body.split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.matches("\\[.*")) plain.add(new TimedLine(0, 0, t, new ArrayList<>(), false));
        }
        return plain;
    }

    private static String extractLyricsString(JsonElement el) {
        if (el == null) return null;
        if (el.isJsonPrimitive() && el.getAsJsonPrimitive().isString()) return el.getAsString();
        if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                String s = extractLyricsString(e);
                if (s != null && s.length() > 20) return s;
            }
            return null;
        }
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            for (String k : new String[]{"ttml", "ttmlContent", "lyrics", "lrc", "content", "text", "syncedLyrics", "plainLyrics"}) {
                JsonElement e = o.get(k);
                if (e != null && e.isJsonPrimitive() && e.getAsJsonPrimitive().isString() && !e.getAsString().trim().isEmpty()) return e.getAsString();
            }
            for (String k : new String[]{"data", "result", "response", "results"}) {
                String s = extractLyricsString(o.get(k));
                if (s != null) return s;
            }
        }
        return null;
    }

    private static Result wrap(Provider p, List<TimedLine> lines) {
        if (!hasText(lines)) return null;
        boolean syl = lines.stream().anyMatch(l -> l.syllable && !l.words.isEmpty());
        return new Result(p, lines, syl);
    }

    private static Result lrclib(String title, String artist, long durationMs) throws Exception {
        int sec = (int) (durationMs / 1000);
        String synced = null;
        if (sec > 0) {
            String url = "https://lrclib.net/api/get?track_name=" + enc(title) + "&artist_name=" + enc(artist) + "&duration=" + sec;
            String body = get(url, "User-Agent", "LiquidSpot");
            if (body != null) {
                JsonObject o = GSON.fromJson(body, JsonObject.class);
                synced = str(o, "syncedLyrics");
            }
        }
        if (synced == null || synced.trim().isEmpty()) {
            String search = get("https://lrclib.net/api/search?track_name=" + enc(title) + "&artist_name=" + enc(artist), "User-Agent", "LiquidSpot");
            if (search != null) {
                JsonArray arr = GSON.fromJson(search, JsonArray.class);
                String want = norm(title);
                String firstSynced = null;
                double best = Double.MAX_VALUE;
                for (JsonElement e : arr) {
                    JsonObject o = e.getAsJsonObject();
                    String s = str(o, "syncedLyrics");
                    if (s == null || s.trim().isEmpty()) continue;
                    if (firstSynced == null) firstSynced = s;
                    if (sec <= 0) {
                        if (want.equals(norm(str(o, "trackName")))) { synced = s; break; }
                    } else {
                        double d = Math.abs(num(o, "duration", 0) - sec);
                        if (d < best) { best = d; synced = s; }
                    }
                }
                if (synced == null) synced = firstSynced;
            }
        }
        return synced == null ? null : wrap(Provider.LRCLIB, parseLrc(synced));
    }

    private static Result bini(String title, String artist, String album, long durationMs) throws Exception {
        HttpUrl.Builder b = HttpUrl.parse("https://lyrics-api.binimum.org/").newBuilder()
                .addQueryParameter("track", title).addQueryParameter("artist", artist);
        if (album != null && !album.trim().isEmpty()) b.addQueryParameter("album", album);
        if (durationMs > 0) b.addQueryParameter("duration", String.valueOf(durationMs / 1000));
        String body = get(b.build().toString());
        if (body == null) return null;
        JsonObject o = GSON.fromJson(body, JsonObject.class);
        JsonArray results = o.has("results") && o.get("results").isJsonArray() ? o.getAsJsonArray("results") : null;
        if (results == null || results.size() == 0) return null;
        String want = norm(title);
        JsonObject best = results.get(0).getAsJsonObject();
        for (JsonElement e : results) {
            JsonObject r = e.getAsJsonObject();
            if (want.equals(norm(str(r, "trackName")))) { best = r; break; }
        }
        String url = str(best, "lyricsUrl");
        if (url == null) return null;
        String ttml = get(url);
        return wrap(Provider.BINI_LYRICS, parseTtml(ttml));
    }

    private static String firstArtist(String artist) {
        if (artist == null) return "";
        String a = artist.split(",")[0].split("&")[0].split(" feat\\.")[0].split(" ft\\.")[0];
        return a.trim();
    }

    private static Result lrcRed(String title, String artist, long durationMs) throws Exception {
        String[] queries = {title + " " + artist, title + " " + firstArtist(artist), title};
        String seen = "";
        for (String q : queries) {
            if (q == null || q.trim().isEmpty() || seen.contains("|" + q + "|")) continue;
            seen += "|" + q + "|";
            Result r = lrcRedQuery(q, title, durationMs);
            if (r != null) return r;
        }
        return null;
    }

    private static Result lrcRedQuery(String query, String title, long durationMs) throws Exception {
        String body = get("https://lrc.red/search.json?q=" + enc(query));
        if (body == null) return null;
        JsonObject o = GSON.fromJson(body, JsonObject.class);
        JsonArray hits = o.has("hits") && o.get("hits").isJsonArray() ? o.getAsJsonArray("hits") : null;
        if (hits == null) return null;
        double sec = durationMs / 1000.0; String bestIsrc = null; double bestDist = Double.MAX_VALUE;
        String want = norm(title);
        String firstIsrc = null;
        for (JsonElement e : hits) {
            JsonObject h = e.getAsJsonObject();
            String isrc = str(h, "isrc");
            if (isrc == null) continue;
            String clean = isrc.replace("-", "").toUpperCase(java.util.Locale.ROOT);
            if (firstIsrc == null) firstIsrc = clean;
            if (durationMs <= 0) {
                if (want.equals(norm(str(h, "title")))) { bestIsrc = clean; break; }
                continue;
            }
            double d = Math.abs(num(h, "duration", sec) - sec);
            if (d <= 6.0 && d < bestDist) { bestDist = d; bestIsrc = clean; }
        }
        if (bestIsrc == null) bestIsrc = durationMs <= 0 && query.trim().equals(title.trim()) ? null : (durationMs <= 0 ? firstIsrc : null);
        if (bestIsrc == null) return null;
        String ttml = get("https://lrc.red/s/" + bestIsrc + ".ttml");
        Result r = wrap(Provider.LRC_RED, parseTtml(ttml));
        return r;
    }

    private static Result better(String title, String artist, String album, long durationMs, String endpoint) throws Exception {
        HttpUrl.Builder b = HttpUrl.parse(endpoint).newBuilder()
                .addQueryParameter("s", title).addQueryParameter("a", artist);
        if (durationMs > 0) b.addQueryParameter("d", String.valueOf(durationMs / 1000));
        if (album != null && !album.trim().isEmpty()) b.addQueryParameter("al", album);
        String body = get(b.build().toString());
        if (body == null) return null;
        Provider p = endpoint.contains("/qq/") ? Provider.BETTER_LYRICS_PORTATO : Provider.BETTER_LYRICS;
        return wrap(p, parseGeneric(body));
    }

    private static Result paxSenix(Config ctx, String title, String artist) throws Exception {
        String key = ctx.paxSenixKey();
        String body = get("https://api.paxsenix.org/lyrics/lrcget?q=" + enc(title + " " + artist),
                "Authorization", "Bearer " + key);
        if (body == null) return null;
        JsonObject o = GSON.fromJson(body, JsonObject.class);
        JsonArray docs = o.has("lyrics") && o.get("lyrics").isJsonArray() ? o.getAsJsonArray("lyrics") : null;
        if (docs != null) {
            for (JsonElement d : docs) {
                List<TimedLine> lines = parseGeneric(d.toString());
                if (hasText(lines)) return wrap(Provider.PAXSENIX, lines);
            }
        }
        return wrap(Provider.PAXSENIX, parseGeneric(body));
    }

    private static Result lyricsPlus(String title, String artist, String album, long durationMs) throws Exception {
        String[] mirrors = {"https://lyricsplus.binimum.org", "https://lyricsplus.prjktla.my.id",
                "https://lyricsplus.atomix.one", "https://lyricsplus.prjktla.workers.dev",
                "https://lyricsplus-seven.vercel.app", "https://lyrics-plus-backend.vercel.app"};
        for (String host : mirrors) {
            try {
                HttpUrl.Builder b = HttpUrl.parse(host + "/v2/lyrics/get").newBuilder()
                        .addQueryParameter("title", title).addQueryParameter("artist", artist);
                if (durationMs > 0) b.addQueryParameter("duration", String.valueOf(durationMs / 1000));
                if (album != null && !album.trim().isEmpty()) b.addQueryParameter("album", album);
                String body = get(b.build().toString());
                if (body == null) continue;
                JsonObject o = GSON.fromJson(body, JsonObject.class);
                JsonArray arr = o.has("lyrics") && o.get("lyrics").isJsonArray() ? o.getAsJsonArray("lyrics") : null;
                if (arr == null) continue;
                List<TimedLine> lines = new ArrayList<>();
                for (JsonElement e : arr) {
                    JsonObject l = e.getAsJsonObject();
                    double start = num(l, "time", 0) / 1000.0;
                    double dur = num(l, "duration", 0) / 1000.0;
                    String text = str(l, "text");
                    List<TimedWord> words = new ArrayList<>();
                    List<TimedWord> bgWords = new ArrayList<>();
                    StringBuilder mainText = new StringBuilder();
                    StringBuilder bgText = new StringBuilder();
                    if (l.has("syllabus") && l.get("syllabus").isJsonArray()) {
                        for (JsonElement s : l.getAsJsonArray("syllabus")) {
                            JsonObject so = s.getAsJsonObject();
                            String wt = str(so, "text");
                            if (wt == null) continue;
                            double ws = num(so, "time", 0) / 1000.0;
                            double wd = num(so, "duration", 0) / 1000.0;
                            boolean background = so.has("isBackground") && so.get("isBackground").isJsonPrimitive()
                                    && so.get("isBackground").getAsBoolean();
                            (background ? bgWords : words).add(new TimedWord(wt, ws, ws + wd, false));
                            (background ? bgText : mainText).append(wt);
                        }
                    }
                    if (!bgWords.isEmpty() && mainText.toString().trim().length() > 0) text = mainText.toString();
                    if (text == null && !words.isEmpty()) text = words.stream().map(w -> w.text).reduce((x, y) -> x + " " + y).orElse("");
                    if (text == null || text.trim().isEmpty()) continue;
                    String agent = null;
                    if (l.has("element") && l.get("element").isJsonObject()) agent = str(l.getAsJsonObject("element"), "singer");
                    String bg = bgText.toString().replaceAll("\\s+", " ").trim();
                    lines.add(new TimedLine(start, start + dur, text.trim(), words, !words.isEmpty(), false,
                            agent, bg.isEmpty() ? null : bg, bgWords));
                }
                if (hasText(lines)) return wrap(Provider.LYRICS_PLUS, lines);
            } catch (Throwable ignored) {}
        }
        return null;
    }

    private static Result unison(String title, String artist, String album, long durationMs) throws Exception {
        HttpUrl.Builder b = HttpUrl.parse("https://unison.boidu.dev/lyrics").newBuilder()
                .addQueryParameter("song", title).addQueryParameter("artist", artist);
        if (album != null && !album.trim().isEmpty()) b.addQueryParameter("album", album);
        if (durationMs > 0) b.addQueryParameter("duration", String.valueOf(durationMs / 1000));
        String body = get(b.build().toString());
        if (body == null) return null;
        JsonObject o = GSON.fromJson(body, JsonObject.class);
        if (o.has("success") && !o.get("success").getAsBoolean()) return null;
        JsonObject data = o.has("data") && o.get("data").isJsonObject() ? o.getAsJsonObject("data") : null;
        if (data == null) return null;
        String lyrics = str(data, "lyrics");
        if (lyrics == null) return null;
        String format = str(data, "format");
        List<TimedLine> lines = "ttml".equalsIgnoreCase(format) ? parseTtml(lyrics) : parseGeneric(lyrics);
        return wrap(Provider.UNISON, lines);
    }

    private static String stripParenthetical(String s) {
        if (s == null) return "";
        String out = s.replaceAll("[(\\uFF08][^)\\uFF09]*[\\)\\uFF09]", "").trim();
        return out.isEmpty() ? s.trim() : out;
    }

    private static String kugouKeyword(String title, String artist, String album) {
        String kw = stripParenthetical(title) + " - " + stripParenthetical(artist);
        if (album != null && !album.trim().isEmpty()) kw += " " + album.trim();
        return kw;
    }

    private static Result kugouDownload(String id, String accessKey) throws Exception {
        String dl = get("https://lyrics.kugou.com/download?fmt=lrc&charset=utf8&client=pc&ver=1&id=" + enc(id)
                + "&accesskey=" + enc(accessKey == null ? "" : accessKey));
        if (dl == null) return null;
        JsonObject o = GSON.fromJson(dl, JsonObject.class);
        String content = str(o, "content");
        if (content == null) return null;
        String lrc = new String(Base64.getDecoder().decode(content), StandardCharsets.UTF_8);
        if (isInstrumentalNotice(lrc)) return null;
        List<TimedLine> lines = parseLrc(lrc);
        return hasText(lines) ? wrap(Provider.KUGOU, lines) : null;
    }

    private static boolean isInstrumentalNotice(String lrc) {
        if (lrc == null) return false;
        List<String> texts = new ArrayList<>();
        for (String raw : lrc.split("\\n")) {
            String t = raw.replaceAll("\\[\\d{1,2}:\\d{2}(?:\\.\\d{2,3})?\\]", "").trim();
            if (!t.isEmpty()) texts.add(t);
        }
        return !texts.isEmpty() && texts.stream().allMatch(t -> t.contains("纯音乐") && t.contains("欣赏"));
    }

    private static List<String[]> kugouCandidates(String url) throws Exception {
        List<String[]> out = new ArrayList<>();
        String body = get(url);
        if (body == null) return out;
        JsonObject o = GSON.fromJson(body, JsonObject.class);
        JsonArray cands = o.has("candidates") && o.get("candidates").isJsonArray() ? o.getAsJsonArray("candidates") : null;
        if (cands == null) return out;
        for (JsonElement e : cands) {
            JsonObject c = e.getAsJsonObject();
            String id = str(c, "id");
            if (id != null) out.add(new String[]{id, str(c, "accesskey")});
        }
        return out;
    }

    private static Result kugou(String title, String artist, String album, long durationMs) throws Exception {
        String keyword = kugouKeyword(title, artist, album);
        int seconds = (int) (durationMs / 1000);
        String search = get("https://mobileservice.kugou.com/api/v3/search/song?version=9108&plat=0&pagesize=8&showtype=0&keyword=" + enc(keyword));
        List<String[]> hashOrder = new ArrayList<>();
        if (search != null) {
            JsonObject o = GSON.fromJson(search, JsonObject.class);
            JsonObject data = o.has("data") && o.get("data").isJsonObject() ? o.getAsJsonObject("data") : null;
            if (data != null && data.has("info") && data.get("info").isJsonArray()) {
                List<JsonObject> infos = new ArrayList<>();
                for (JsonElement e : data.getAsJsonArray("info")) infos.add(e.getAsJsonObject());
                if (seconds > 0) infos.sort((a, b) -> Double.compare(
                        Math.abs(num(a, "duration", seconds) - seconds),
                        Math.abs(num(b, "duration", seconds) - seconds)));
                for (JsonObject info : infos) {
                    String h = str(info, "hash");
                    double d = num(info, "duration", seconds);
                    if (h == null) continue;
                    if (seconds > 0 && d >= 0 && Math.abs(d - seconds) > 8) continue;
                    hashOrder.add(new String[]{h});
                }
            }
        }
        for (String[] hash : hashOrder) {
            for (String[] cand : kugouCandidates("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&hash=" + enc(hash[0]))) {
                Result r = kugouDownload(cand[0], cand[1]);
                if (r != null) return r;
            }
        }
        for (String[] cand : kugouCandidates("https://lyrics.kugou.com/search?ver=1&man=yes&client=pc&keyword=" + enc(keyword)
                + "&duration=" + (durationMs > 0 ? durationMs : ""))) {
            Result r = kugouDownload(cand[0], cand[1]);
            if (r != null) return r;
        }
        return null;
    }

    private static Result musixmatch(String title, String artist, long durationMs) throws Exception {
        String url = "https://apic.musixmatch.com/ws/1.1/track.search?format=json&q_track=" + enc(title)
                + "&q_artist=" + enc(artist) + "&app_id=mobile-app-v1.0&page_size=1";
        String body = get(url);
        if (body == null) return null;
        log("Musixmatch track.search answered but rich/subtitle fetch is not implemented without a signing key");
        return null;
    }

    private static Result genius(String title, String artist) throws Exception {
        String search = get("https://genius.com/api/search/multi?q=" + enc(title + " " + artist),
                "User-Agent", "LiquidSpot");
        if (search == null) return null;
        JsonObject o = GSON.fromJson(search, JsonObject.class);
        String path = findGeniusPath(o);
        if (path == null) return null;
        String page = get("https://genius.com" + path, "User-Agent", "LiquidSpot");
        if (page == null) return null;
        Pattern container = Pattern.compile("data-lyrics-container[^>]*>(.*?)</div>", Pattern.DOTALL);
        Matcher m = container.matcher(page);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String html = m.group(1).replaceAll("(?i)<br[^>]*>", "\n").replaceAll("<[^>]+>", "");
            html = html.replace("&amp;", "&").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ");
            sb.append(html).append("\n");
        }
        List<TimedLine> lines = new ArrayList<>();
        for (String line : sb.toString().split("\n")) {
            String t = line.trim();
            if (!t.isEmpty() && !t.startsWith("[")) lines.add(new TimedLine(0, 0, t, new ArrayList<>(), false));
            else if (t.startsWith("[")) lines.add(new TimedLine(0, 0, t, new ArrayList<>(), false));
        }
        return wrap(Provider.GENIUS, lines);
    }

    private static String findGeniusPath(JsonElement el) {
        if (el == null) return null;
        if (el.isJsonObject()) {
            JsonObject o = el.getAsJsonObject();
            if (o.has("path") && o.has("title")) {
                String p = str(o, "path");
                if (p != null && p.contains("lyrics")) return p;
            }
            for (java.util.Map.Entry<String, JsonElement> e : o.entrySet()) {
                String p = findGeniusPath(e.getValue());
                if (p != null) return p;
            }
        } else if (el.isJsonArray()) {
            for (JsonElement e : el.getAsJsonArray()) {
                String p = findGeniusPath(e);
                if (p != null) return p;
            }
        }
        return null;
    }
}
