package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The full-screen hero behind Home: the TMDB backdrop of the title focused in the Continue watching shelf, with its
 * name, year, rating, length, genres and overview. A title TMDB does not know shows the row's own poster and name.
 */
final class TmdbHero {
    private final Activity act;
    private final String apiKey;
    private final float density;
    private final View container, textBlock;
    private final ImageView backA, backB;
    private final TextView title, meta, overview, episodeLine;
    private final ImageView appIcon;
    private final Map<String, TmdbClient.Info> infos = new HashMap<>();
    private final Map<String, Boolean> pending = new HashMap<>();
    private final ExecutorService pool = Executors.newFixedThreadPool(3);
    private WatchNextReader.Item current;
    private int generation;
    private boolean frontIsA = true;

    TmdbHero(Activity act, View container, String apiKey, float density) {
        this.act = act;
        this.apiKey = apiKey;
        this.density = density;
        this.container = container;
        this.backA = container.findViewById(R.id.tmdbBackA);
        this.backB = container.findViewById(R.id.tmdbBackB);
        this.textBlock = container.findViewById(R.id.tmdbText);
        this.appIcon = container.findViewById(R.id.tmdbAppIcon);
        appIcon.setClipToOutline(true);
        appIcon.setOutlineProvider(new android.view.ViewOutlineProvider() {
            @Override
            public void getOutline(View v, android.graphics.Outline o) {
                o.setOval(0, 0, v.getWidth(), v.getHeight());   // a full circle
            }
        });
        this.title = container.findViewById(R.id.tmdbTitle);
        this.meta = container.findViewById(R.id.tmdbMeta);
        this.episodeLine = container.findViewById(R.id.tmdbEpisode);
        this.overview = container.findViewById(R.id.tmdbOverview);
        backA.setAlpha(1f);
        backB.setAlpha(0f);
        // Left scrim for the text, bottom scrim for the shelf and dock, a light top one for the clock and pills.
        container.findViewById(R.id.tmdbScrimSide).setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{0xF2000000, 0xCC000000, 0x00000000}));
        container.findViewById(R.id.tmdbScrimBottom).setBackground(new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,
                new int[]{0xFF000000, 0xB3000000, 0x00000000}));
        container.findViewById(R.id.tmdbScrimTop).setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{0x99000000, 0x00000000}));
        container.setVisibility(View.GONE);
    }

    /** Takes the hero off Home (the option was switched off). */
    void hide() {
        container.setVisibility(View.GONE);
        current = null;
    }

    /** Aligns the text with the app tiles' left edge. */
    void setTextStart(int px) {
        textBlock.setPaddingRelative(Math.max(0, px), 0, 0, 0);
    }

    /** The text block's bottom edge, in root coordinates (just above the shelf). */
    void placeAbove(float bottomY) {
        textBlock.setY(Math.max(0, bottomY - textBlock.getHeight()));
    }

    /**
     * The shelf (re)loaded: every card's metadata and backdrop is fetched now, not when it takes focus, so moving along the
     * shelf is instant. The hero shows the card holding focus, or the most recent title when focus is elsewhere on Home.
     */
    void setItems(List<WatchNextReader.Item> items, int focusIndex) {
        if (items.isEmpty()) {
            container.setVisibility(View.GONE);
            current = null;
            return;
        }
        container.setVisibility(View.VISIBLE);
        for (WatchNextReader.Item it : items) {
            lookupAsync(it, false);
            ContinueWatchingRow.PosterLoader.prefetch(act, it.poster, 1280);   // the fallback backdrop for a title TMDB lacks
        }
        show(items.get(focusIndex >= 0 && focusIndex < items.size() ? focusIndex : 0));
    }

    /** A row is an episode of a series when it names an episode or carries a season or episode number. */
    private static boolean isEpisode(WatchNextReader.Item it) {
        return (it.episodeTitle != null && !it.episodeTitle.isEmpty()) || it.season != null || it.episode != null;
    }

    private static String key(WatchNextReader.Item it) {
        return it.title + "|" + isEpisode(it);
    }

    /** "Season 1 · Episode 9 · Boca", with whatever of the three the app published; null for a film. */
    private static String episodeText(WatchNextReader.Item it) {
        StringBuilder sb = new StringBuilder();
        if (it.season != null) sb.append("Season ").append(it.season);
        if (it.episode != null) {
            if (sb.length() > 0) sb.append("  \u00b7  ");
            sb.append("Episode ").append(it.episode);
        }
        if (it.episodeTitle != null && !it.episodeTitle.isEmpty()) {
            if (sb.length() > 0) sb.append("  \u00b7  ");
            sb.append(it.episodeTitle);
        }
        return sb.length() == 0 ? null : sb.toString();
    }

    private void lookupAsync(WatchNextReader.Item it, boolean redraw) {
        String k = key(it);
        synchronized (infos) {
            if (infos.containsKey(k) || pending.containsKey(k)) return;
            pending.put(k, true);
        }
        boolean tvHint = isEpisode(it);
        pool.execute(() -> {
            TmdbClient.Info info = TmdbClient.lookup(act.getApplicationContext(), apiKey, it.title, tvHint);
            synchronized (infos) {
                pending.remove(k);
                infos.put(k, info);   // null is remembered too: a miss is not asked again until the next launch
            }
            act.runOnUiThread(() -> {
                if (act.isDestroyed()) return;
                if (info != null) ContinueWatchingRow.PosterLoader.prefetch(act, info.backdropUrl, 1280);
                if (current != null && key(current).equals(k)) show(current);
            });
        });
    }

    /** Shows an item: the TMDB details when known (a later arrival redraws), otherwise the row's own title and poster. */
    void show(WatchNextReader.Item it) {
        current = it;
        int gen = ++generation;
        String k = key(it);
        TmdbClient.Info info;
        boolean known;
        synchronized (infos) {
            known = infos.containsKey(k);
            info = infos.get(k);
        }
        if (!known) lookupAsync(it, true);

        // The app the title is in, above its name.
        try {
            android.graphics.drawable.Drawable d = act.getPackageManager().getApplicationIcon(it.pkg);
            appIcon.setImageDrawable(d);
            appIcon.setVisibility(View.VISIBLE);
        } catch (android.content.pm.PackageManager.NameNotFoundException e) {
            appIcon.setVisibility(View.GONE);
        }
        title.setText(info != null && info.title != null ? info.title : it.title);
        StringBuilder m = new StringBuilder();
        if (info != null) {
            append(m, info.year);
            if (info.rating >= 0) append(m, String.format(java.util.Locale.US, "★ %.1f", info.rating));
            append(m, info.length);
            append(m, info.genres);
        }
        String episode = episodeText(it);
        episodeLine.setText(episode);
        episodeLine.setVisibility(episode != null ? View.VISIBLE : View.GONE);
        meta.setText(m);
        meta.setVisibility(m.length() > 0 ? View.VISIBLE : View.GONE);
        String text = info != null ? (info.overview != null ? info.overview : info.tagline) : null;
        overview.setText(text);
        overview.setVisibility(text != null ? View.VISIBLE : View.GONE);
        textBlock.post(() -> {
            // The block grew or shrank with its text: keep its bottom edge where the shelf wants it.
            if (textBlockBottom > 0) placeAbove(textBlockBottom);
        });

        String url = info != null && info.backdropUrl != null ? info.backdropUrl : it.poster;
        if (url == null) return;
        ContinueWatchingRow.PosterLoader.load(act, url, 1280, bmp -> {
            if (bmp != null && gen == generation && !act.isDestroyed()) setBackdrop(bmp);
        });
    }

    private float textBlockBottom;

    float bottom() {
        return textBlockBottom;
    }

    void remember(float bottomY) {
        textBlockBottom = bottomY;
        placeAbove(bottomY);
    }

    private static void append(StringBuilder sb, String part) {
        if (part == null || part.isEmpty()) return;
        if (sb.length() > 0) sb.append("  ·  ");
        sb.append(part);
    }

    private void setBackdrop(Bitmap bmp) {
        ImageView front = frontIsA ? backA : backB;
        ImageView back = frontIsA ? backB : backA;
        back.setImageBitmap(bmp);
        back.setAlpha(0f);
        back.animate().alpha(1f).setDuration(450).start();
        front.animate().alpha(0f).setDuration(450).start();
        frontIsA = !frontIsA;
    }
}
