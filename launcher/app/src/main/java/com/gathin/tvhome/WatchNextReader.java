package com.gathin.tvhome;

import android.content.Context;
import android.database.Cursor;
import android.media.tv.TvContract;
import android.net.Uri;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Reads the rows other apps publish to the system's Continue watching (Watch Next) table; see docs/watch-next.md.
 * Needs READ_TV_LISTINGS (a runtime permission: {@code pm grant com.gathin.tvhome android.permission.READ_TV_LISTINGS}).
 * Without ACCESS_ALL_EPG_DATA a reader sees only rows marked searchable, so an app that publishes none shows nothing.
 */
final class WatchNextReader {
    private static final String TAG = "WatchNext";

    /** One Continue watching row. */
    static final class Item {
        String pkg, title, episodeTitle, poster, intentUri, season, episode;
        long engagedAt, position, duration;
        int type;

        /** Fraction watched, 0 to 1, or -1 when unknown. */
        float progress() {
            return duration > 0 && position >= 0 ? Math.min(1f, position / (float) duration) : -1f;
        }
    }

    private WatchNextReader() {}

    /** The rows, most recently engaged first. Blocking: call off the main thread. Empty when not permitted. */
    static List<Item> read(Context ctx) {
        List<Item> out = new ArrayList<>();
        // Named columns: a null projection fails on some firmware (split_id), and a selection needs a privileged permission.
        String[] cols = {
                TvContract.WatchNextPrograms.COLUMN_PACKAGE_NAME,
                TvContract.WatchNextPrograms.COLUMN_TITLE,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_TITLE,
                TvContract.WatchNextPrograms.COLUMN_POSTER_ART_URI,
                TvContract.WatchNextPrograms.COLUMN_INTENT_URI,
                TvContract.WatchNextPrograms.COLUMN_LAST_ENGAGEMENT_TIME_UTC_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_LAST_PLAYBACK_POSITION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_DURATION_MILLIS,
                TvContract.WatchNextPrograms.COLUMN_WATCH_NEXT_TYPE,
                TvContract.WatchNextPrograms.COLUMN_SEASON_DISPLAY_NUMBER,
                TvContract.WatchNextPrograms.COLUMN_EPISODE_DISPLAY_NUMBER,
        };
        try (Cursor c = ctx.getContentResolver().query(TvContract.WatchNextPrograms.CONTENT_URI, cols, null, null, null)) {
            if (c == null) return out;
            while (c.moveToNext()) {
                Item it = new Item();
                it.pkg = c.getString(0);
                it.title = c.getString(1);
                it.episodeTitle = c.getString(2);
                it.poster = c.getString(3);
                it.intentUri = c.getString(4);
                it.engagedAt = c.getLong(5);
                it.position = c.isNull(6) ? -1 : c.getLong(6);
                it.duration = c.isNull(7) ? -1 : c.getLong(7);
                it.type = c.isNull(8) ? -1 : c.getInt(8);
                it.season = clean(c.getString(9));
                it.episode = clean(c.getString(10));
                if (it.pkg != null && it.intentUri != null) out.add(it);
            }
        } catch (RuntimeException e) {   // SecurityException before the permission is granted, or a provider that errors
            Log.w(TAG, "could not read Watch Next: " + e);
        }
        Collections.sort(out, (a, b) -> Long.compare(b.engagedAt, a.engagedAt));
        return out;
    }

    private static String clean(String v) {
        return v == null || v.trim().isEmpty() || "null".equals(v) ? null : v.trim();
    }

    /** TEMP: which apps publish preview programs (channel rows), and how many. */
    static void probePreview(Context ctx) {
        java.util.Map<String, Integer> counts = new java.util.TreeMap<>();
        java.util.Map<String, String> sample = new java.util.HashMap<>();
        try (Cursor c = ctx.getContentResolver().query(TvContract.PreviewPrograms.CONTENT_URI,
                new String[]{TvContract.PreviewPrograms.COLUMN_PACKAGE_NAME, TvContract.PreviewPrograms.COLUMN_TITLE}, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String p = c.getString(0);
                counts.put(p, counts.containsKey(p) ? counts.get(p) + 1 : 1);
                if (!sample.containsKey(p)) sample.put(p, c.getString(1));
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "preview probe failed: " + e);
        }
        for (java.util.Map.Entry<String, Integer> e : counts.entrySet())
            Log.i(TAG, "PREVIEW " + e.getKey() + " rows=" + e.getValue() + " e.g. " + sample.get(e.getKey()));
        Log.i(TAG, "PREVIEW done, apps=" + counts.size());
    }

    /** Logs what the TV has, per app, for finding out which apps publish anything. */
    static void probe(Context ctx) {
        List<Item> items = read(ctx);
        Log.i(TAG, "rows=" + items.size());
        for (Item it : items) {
            Log.i(TAG, it.pkg + " | type=" + it.type + " | " + it.title + (it.episodeTitle != null ? " / " + it.episodeTitle : "")
                    + " | engaged=" + it.engagedAt + " | progress=" + it.progress()
                    + " | poster=" + it.poster
                    + " | intent=" + it.intentUri);
        }
    }
}
