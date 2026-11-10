package com.gathin.tvhome;

import android.content.Context;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * The Continue watching shelf's image cache on disk, in the app's cache directory. Everything the shelf keeps there (these
 * images and the TMDB lookups in tmdb/) stays under {@link #limitBytes}: past it the least recently used files go first.
 */
final class DiskCache {
    /** The shelf's cache size in bytes, from the Launcher settings dialog (0 to 100 MB; 50 MB until changed). */
    static long limitBytes(Context ctx) {
        int mb = UserPrefs.integer(ctx, "image_cache_mb", 50);
        return Math.max(0, Math.min(100, mb)) * 1024L * 1024;
    }

    /** Applies a new size at once: everything goes at 0 MB, otherwise the least recently used files until it fits. */
    static void applyLimit(Context ctx) {
        if (limitBytes(ctx) == 0) clear(ctx);
        else trim(ctx);
    }

    private DiskCache() {}

    private static File dir(Context ctx) {
        File d = new File(ctx.getCacheDir(), "images");
        d.mkdirs();
        return d;
    }

    private static File file(Context ctx, String url) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-1").digest(url.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : h) sb.append(String.format("%02x", b));
            return new File(dir(ctx), sb.toString());
        } catch (Exception e) {
            return new File(dir(ctx), Integer.toHexString(url.hashCode()));
        }
    }

    /** Deletes everything the shelf has cached (images and TMDB lookups). */
    static void clear(Context ctx) {
        for (String sub : new String[]{"images", "tmdb"}) {
            File[] list = new File(ctx.getCacheDir(), sub).listFiles();
            if (list != null) for (File f : list) f.delete();
        }
    }

    /** The cached bytes, or null. A hit counts as use, so it outlives older files at the next trim. */
    static byte[] get(Context ctx, String url) {
        File f = file(ctx, url);
        if (!f.isFile()) return null;
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] data = new byte[(int) f.length()];
            int off = 0;
            while (off < data.length) {
                int n = in.read(data, off, data.length - off);
                if (n < 0) return null;
                off += n;
            }
            f.setLastModified(System.currentTimeMillis());
            return data;
        } catch (Exception e) {
            return null;
        }
    }

    static void put(Context ctx, String url, byte[] data) {
        if (limitBytes(ctx) == 0) return;   // caching is off
        File f = file(ctx, url);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        } catch (Exception e) {
            f.delete();
            return;
        }
        trim(ctx);
    }

    /** Deletes the least recently used files while the shelf's cache is over the limit. */
    static synchronized void trim(Context ctx) {
        List<File> files = new ArrayList<>();
        long total = 0;
        for (String sub : new String[]{"images", "tmdb"}) {
            File[] list = new File(ctx.getCacheDir(), sub).listFiles();
            if (list == null) continue;
            for (File f : list) {
                if (!f.isFile()) continue;
                files.add(f);
                total += f.length();
            }
        }
        long limit = limitBytes(ctx);
        if (total <= limit) return;
        Collections.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (File f : files) {
            if (total <= limit * 9 / 10) break;
            long len = f.length();
            if (f.delete()) total -= len;
        }
    }
}
