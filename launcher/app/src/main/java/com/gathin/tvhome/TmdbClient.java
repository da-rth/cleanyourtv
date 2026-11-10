package com.gathin.tvhome;

import android.content.Context;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

/**
 * Looks a title up on TMDB (themoviedb.org) for the Continue watching hero: backdrop, overview, rating, year, genres and
 * length. Results are cached on disk (a found title for 14 days, a miss for a day), so each title costs one search and one
 * details request. This product uses the TMDB API but is not endorsed or certified by TMDB.
 */
final class TmdbClient {
    private static final String TAG = "Tmdb";
    private static final String API = "https://api.themoviedb.org/3";
    static final String IMAGE_BASE = "https://image.tmdb.org/t/p/";
    private static final long TTL_MS = 14L * 24 * 3600 * 1000, MISS_TTL_MS = 24L * 3600 * 1000;

    /** What the hero shows for a title. Any field may be null. */
    static final class Info {
        String title, overview, backdropUrl, posterUrl, year, genres, length, tagline;
        double rating = -1;
        boolean tv;
    }

    private TmdbClient() {}

    /** Blocking: call off the main thread. Null when TMDB has nothing for the title or the key is refused. */
    static Info lookup(Context ctx, String apiKey, String title, boolean tvHint) {
        if (apiKey == null || apiKey.isEmpty() || title == null || title.trim().isEmpty()) return null;
        boolean useDisk = DiskCache.limitBytes(ctx) > 0;   // 0 MB turns the disk cache off
        File cache = cacheFile(ctx, title, tvHint);
        Info cached = useDisk ? readCache(cache) : NOT_CACHED;
        if (cached != NOT_CACHED) return cached;
        try {
            JSONObject hit = search(apiKey, title, tvHint);
            if (hit == null) {
                if (useDisk) writeCache(cache, null);
                return null;
            }
            String type = hit.optString("media_type");
            JSONObject d = get(apiKey, "/" + type + "/" + hit.getInt("id"), "");
            Info info = new Info();
            info.tv = "tv".equals(type);
            info.title = d.optString(info.tv ? "name" : "title", title);
            info.overview = emptyToNull(d.optString("overview"));
            info.tagline = emptyToNull(d.optString("tagline"));
            info.backdropUrl = imageUrl("w1280", d.optString("backdrop_path"));
            info.posterUrl = imageUrl("w500", d.optString("poster_path"));
            String date = d.optString(info.tv ? "first_air_date" : "release_date");
            info.year = date.length() >= 4 ? date.substring(0, 4) : null;
            double va = d.optDouble("vote_average", 0);
            info.rating = d.optInt("vote_count", 0) > 0 ? va : -1;
            JSONArray g = d.optJSONArray("genres");
            if (g != null) {
                StringBuilder sb = new StringBuilder();
                for (int i = 0; i < g.length() && i < 3; i++) {
                    if (sb.length() > 0) sb.append(", ");
                    sb.append(g.getJSONObject(i).optString("name"));
                }
                info.genres = emptyToNull(sb.toString());
            }
            if (info.tv) {
                int seasons = d.optInt("number_of_seasons", 0);
                if (seasons > 0) info.length = seasons + (seasons == 1 ? " season" : " seasons");
            } else {
                int min = d.optInt("runtime", 0);
                if (min > 0) info.length = min >= 60 ? (min / 60) + "h " + (min % 60) + "m" : min + "m";
            }
            if (useDisk) writeCache(cache, info);
            return info;
        } catch (Exception e) {
            Log.w(TAG, "lookup failed for \"" + title + "\": " + e);
            return null;   // not cached: a network error should retry next time
        }
    }

    /** The best movie or series for a title: an exact name first, a series when an episode was being watched. */
    private static JSONObject search(String key, String title, boolean tvHint) throws Exception {
        JSONObject res = get(key, "/search/multi", "&include_adult=false&query=" + URLEncoder.encode(title, "UTF-8"));
        JSONArray results = res.optJSONArray("results");
        if (results == null) return null;
        String want = norm(title);
        JSONObject best = null;
        double bestScore = -1;
        for (int i = 0; i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String type = r.optString("media_type");
            if (!"movie".equals(type) && !"tv".equals(type)) continue;
            String name = r.optString("tv".equals(type) ? "name" : "title");
            String original = r.optString("tv".equals(type) ? "original_name" : "original_title");
            double score = Math.min(r.optDouble("popularity", 0), 100) / 100.0;   // a tie-breaker, 0 to 1
            if (norm(name).equals(want) || norm(original).equals(want)) score += 10;
            if (tvHint == "tv".equals(type)) score += tvHint ? 5 : 0.5;
            if (r.optString("backdrop_path").isEmpty() || "null".equals(r.optString("backdrop_path"))) score -= 0.5;
            if (score > bestScore) {
                bestScore = score;
                best = r;
            }
        }
        return best;
    }

    private static JSONObject get(String key, String path, String query) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(API + path + "?api_key=" + key + "&language=en-US" + query).openConnection();
        c.setConnectTimeout(8000);
        c.setReadTimeout(8000);
        try {
            int code = c.getResponseCode();
            if (code != 200) throw new Exception("TMDB answered " + code);
            try (InputStream in = c.getInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                return new JSONObject(out.toString("UTF-8"));
            }
        } finally {
            c.disconnect();
        }
    }

    private static String norm(String s) {
        return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
    }

    private static String emptyToNull(String s) {
        return s == null || s.isEmpty() || "null".equals(s) ? null : s;
    }

    private static String imageUrl(String size, String path) {
        return path == null || path.isEmpty() || "null".equals(path) ? null : IMAGE_BASE + size + path;
    }

    // ---- disk cache ------------------------------------------------------------------------------

    private static final Info NOT_CACHED = new Info();

    private static File cacheFile(Context ctx, String title, boolean tvHint) {
        File dir = new File(ctx.getCacheDir(), "tmdb");
        dir.mkdirs();
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest((norm(title) + "|" + tvHint).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return new File(dir, sb + ".json");
        } catch (Exception e) {
            return new File(dir, Integer.toHexString(title.hashCode()) + ".json");
        }
    }

    /** NOT_CACHED when absent or expired, null for a cached miss, else the info. */
    private static Info readCache(File f) {
        if (!f.isFile()) return NOT_CACHED;
        try (FileInputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            JSONObject j = new JSONObject(out.toString("UTF-8"));
            long age = System.currentTimeMillis() - j.optLong("t");
            if (j.optBoolean("none")) return age < MISS_TTL_MS ? null : NOT_CACHED;
            if (age > TTL_MS) return NOT_CACHED;
            Info i = new Info();
            i.title = emptyToNull(j.optString("title"));
            i.overview = emptyToNull(j.optString("overview"));
            i.tagline = emptyToNull(j.optString("tagline"));
            i.backdropUrl = emptyToNull(j.optString("backdrop"));
            i.posterUrl = emptyToNull(j.optString("poster"));
            i.year = emptyToNull(j.optString("year"));
            i.genres = emptyToNull(j.optString("genres"));
            i.length = emptyToNull(j.optString("length"));
            i.rating = j.optDouble("rating", -1);
            i.tv = j.optBoolean("tv");
            return i;
        } catch (Exception e) {
            return NOT_CACHED;
        }
    }

    private static void writeCache(File f, Info i) {
        try {
            JSONObject j = new JSONObject();
            j.put("t", System.currentTimeMillis());
            if (i == null) {
                j.put("none", true);
            } else {
                j.put("title", i.title).put("overview", i.overview).put("tagline", i.tagline).put("backdrop", i.backdropUrl)
                        .put("poster", i.posterUrl).put("year", i.year).put("genres", i.genres).put("length", i.length)
                        .put("rating", i.rating).put("tv", i.tv);
            }
            try (FileOutputStream out = new FileOutputStream(f)) {
                out.write(j.toString().getBytes(StandardCharsets.UTF_8));
            }
        } catch (Exception ignored) {
            // A cache that cannot be written just costs another request next time.
        }
    }
}
