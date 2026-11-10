package com.gathin.tvhome;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The Continue watching shelf on Home (config.xml / theme "continue_watching"): a row of cards for the titles other
 * apps publish to Android TV's Watch Next table, most recently watched first. OK on a card runs the row's own deep link,
 * which opens that app on that title. Posters are the apps' own artwork, fetched once and kept in memory.
 */
final class ContinueWatchingRow {

    /** What the shelf asks of Home: where focus goes from the shelf's edges. */
    interface Nav {
        void up();
        void down();
        /** Focus came to the shelf: the dock's glass highlight and title step aside. */
        void entered();
        /** A card took focus: Home's glass highlight moves to it, as to an app tile; snap while the shelf scrolls. */
        void cardFocused(View card, boolean snap, int accent);
    }

    private static final int MAX_ITEMS = 8;
    private static final int ICON_DP = 18;
    private static final int CARD_W_DP = 232, POSTER_H_DP = 131, TEXT_H_DP = 46, GAP_DP = 14;
    /** Card size and spacing in px: the app tiles' own (setMetrics), so the shelf and the dock line up. */
    private int cardW, posterH, cellW, padL, padR, startPx = -1;
    private boolean loadedOnce;
    private int boundsKey;
    private boolean animations = true, reducedMotion, advancedEffects, overshoot;

    /** The same motion settings the app tiles use. */
    void setMotion(boolean animations, boolean reducedMotion, boolean advancedEffects, boolean overshoot) {
        this.animations = animations;
        this.reducedMotion = reducedMotion;
        this.advancedEffects = advancedEffects;
        this.overshoot = overshoot;
    }

    /** Offset from the section's top to the cards' top, so the shelf can sit with its cards where the dock's tiles are. */
    float cardTopOffset() {
        View frame = section.findViewById(R.id.continueClip);
        return (frame != null ? frame.getTop() : 0) + list.getTop() + list.getPaddingTop();
    }

    private final Activity act;
    private final float density;
    private final View section;
    private final RecyclerView list;
    private final CardAdapter adapter = new CardAdapter();
    private final Nav nav;
    /** The hero follows the shelf: told of the list when it loads and of each card as it takes focus. */
    interface Listener {
        /** @param focusIndex the card holding focus after the reload, or -1 when focus is elsewhere on Home */
        void onItems(List<WatchNextReader.Item> items, int focusIndex);
        void onFocused(WatchNextReader.Item item);
        void onLongPress(WatchNextReader.Item item, View card);
    }

    private Listener listener;

    void setListener(Listener l) {
        this.listener = l;
    }

    float top() {
        return section.getY();
    }
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean loading;
    private int lastFocused;

    ContinueWatchingRow(Activity act, View section, RecyclerView list, float density, Nav nav) {
        this.act = act;
        this.section = section;
        this.list = list;
        this.density = density;
        this.nav = nav;
        this.cardW = Math.round(CARD_W_DP * density);
        this.posterH = Math.round(POSTER_H_DP * density);
        this.cellW = Math.round((CARD_W_DP + GAP_DP) * density);
        this.padL = this.padR = Math.round(10 * density);
        // The app grid's single row, copied: it scrolls just enough to bring the focused card's whole cell inside the padding,
        // and cells are a column wide, so the row moves a whole card at a time and the cards stay on their columns.
        list.setLayoutManager(new LinearLayoutManager(act, LinearLayoutManager.HORIZONTAL, false) {
            @Override
            public boolean requestChildRectangleOnScreen(@NonNull RecyclerView parent, @NonNull View child,
                                                         @NonNull android.graphics.Rect rect, boolean immediate,
                                                         boolean focusedChildVisible) {
                int left = child.getLeft();
                int right = child.getRight();
                int dx;
                if (left < getPaddingLeft()) dx = left - getPaddingLeft();
                else if (right > getWidth() - getPaddingRight()) dx = right - (getWidth() - getPaddingRight());
                else dx = 0;
                if (dx == 0) return false;
                if (immediate || reducedMotion || !animations) parent.scrollBy(dx, 0);
                else parent.smoothScrollBy(dx, 0);
                return true;
            }
        });
        list.setAdapter(adapter);
        list.setItemAnimator(null);
        // The glass highlight follows a focused card as the shelf scrolls under it.
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override
            public void onScrolled(@NonNull RecyclerView rv, int dx, int dy) {
                View f = focusedCard();
                if (f != null && dx != 0) nav.cardFocused(f, true, accentOf(f));
            }
        });
        // Clipped at the list's own bounds (set to the app grid's in setMetrics) so cards past the edge stay hidden until
        // scrolled to; the padding stays drawable for a focused card's zoom.
        list.setClipChildren(true);
        list.setClipToPadding(false);
        int pad = Math.round(10 * density);
        list.setPadding(pad, pad, pad, pad);
        section.setPaddingRelative(0, 0, 0, 0);
        // The section decides whether the list may draw outside its own bounds: it may not, so cards past the shelf's
        // edges stay hidden until scrolled to, like the app grid.
        if (section instanceof ViewGroup) ((ViewGroup) section).setClipChildren(true);
        section.setVisibility(View.GONE);
    }

    /**
     * Match the app grid: the tile's width and height, the width of a column (a cell holds one tile, centred), the grid's
     * own left and right padding, and the screen x of the first tile.
     */
    void setMetrics(int w, int h, int cell, int gridPadL, int gridPadR, int start, int boundsLeft, int boundsRight, int screenW) {
        int key = (boundsLeft * 31 + boundsRight) * 31 + cell;
        if (w <= 0 || h <= 0 || cell <= 0 || (w == cardW && h == posterH && cell == cellW && gridPadL == padL && gridPadR == padR
                && start == startPx && key == boundsKey)) return;
        cardW = w;
        posterH = h;
        cellW = cell;
        padL = gridPadL;
        padR = gridPadR;
        startPx = start;
        boundsKey = key;
        int pad = Math.round(10 * density);
        // The shelf is exactly as wide as the app grid, so it clips where the grid does.
        section.setPaddingRelative(Math.max(0, boundsLeft), 0, Math.max(0, screenW - boundsRight), 0);
        int inset = Math.max(0, start - boundsLeft);
        // The frame holding the list is exactly the app grid's width, so the shelf ends where the grid does and cards past the
        // edge stay hidden until scrolled to.
        View frame = section.findViewById(R.id.continueClip);
        if (frame != null && frame.getLayoutParams() != null) {
            ViewGroup.LayoutParams flp = frame.getLayoutParams();
            int width = Math.max(1, boundsRight - boundsLeft);
            if (flp.width != width) {
                flp.width = width;
                frame.setLayoutParams(flp);
                // Asked for from inside a layout pass, the request can be dropped: ask again on the next frame.
                section.post(() -> {
                    frame.requestLayout();
                    section.requestLayout();
                });
            }
        }
        list.setPaddingRelative(padL, pad, padR, pad);
        View label = section.findViewById(R.id.continueTitle);
        if (label != null) {
            if (label.getLayoutParams() instanceof ViewGroup.MarginLayoutParams) ((ViewGroup.MarginLayoutParams) label.getLayoutParams()).setMarginStart(0);
            label.setPaddingRelative(inset, 0, 0, 0);
        }
        list.setAdapter(null);   // rebuild the cards at the new size
        list.setAdapter(adapter);
    }

    /** True once the first read has finished, whether or not it found anything. */
    boolean loadedOnce() {
        return loadedOnce;
    }

    /** Takes the shelf off Home (the option was switched off): hidden, no scroll hook, no cards. */
    void shutDown() {
        section.animate().cancel();
        section.setVisibility(View.GONE);
        list.clearOnScrollListeners();
        list.setAdapter(null);
    }

    /** The item shown on a card, or null. */
    WatchNextReader.Item itemFor(View card) {
        RecyclerView.ViewHolder vh = list.findContainingViewHolder(card);
        int pos = vh == null ? RecyclerView.NO_POSITION : vh.getBindingAdapterPosition();
        return adapter.itemAt(pos);
    }

    /**
     * Shows or hides the whole shelf without changing its place: hidden it is faded out and takes no focus, so it can come
     * back (Up from the app tiles) exactly where it was.
     */
    void setShown(boolean shown, boolean animate) {
        if (section instanceof ViewGroup) {
            ((ViewGroup) section).setDescendantFocusability(shown ? ViewGroup.FOCUS_AFTER_DESCENDANTS : ViewGroup.FOCUS_BLOCK_DESCENDANTS);
        }
        section.animate().cancel();
        if (!animate || !animations || reducedMotion) section.setAlpha(shown ? 1f : 0f);
        else section.animate().alpha(shown ? 1f : 0f).setDuration(240).start();
    }

    /** How much of the title was watched, 0 to 1, or -1 when the app published no progress. */
    float progressOf(View card) {
        WatchNextReader.Item it = itemFor(card.getParent() instanceof View ? (View) card.getParent() : card);
        return it == null ? -1f : it.progress();
    }

    /** The colour of a card's glass highlight: its poster's vivid colour, as an app tile uses its banner's. */
    int accentOf(View card) {
        View cell = card.getParent() instanceof View ? (View) card.getParent() : null;
        Object tag = cell == null ? null : cell.getTag();
        if (!(tag instanceof Views)) return 0;
        Views v = (Views) tag;
        if (v.accent == 0 && v.poster.getDrawable() != null) v.accent = AppAdapter.vividColor(v.poster.getDrawable());
        return v.accent;
    }

    /** The card holding focus, or null. */
    View focusedCard() {
        View cell = list.getFocusedChild();
        View card = cell == null ? null : cell.findViewById(R.id.card);
        return card != null && card.isFocused() ? card : null;
    }

    boolean hasFocus() {
        return list.hasFocus();
    }

    // ---- removing items --------------------------------------------------------------------------

    /**
     * Other apps own their Watch Next rows and cannot be edited from here, so a removed title is remembered on this
     * side: hidden until the app publishes it again with a later engagement time (that is, it was watched again).
     */
    private android.content.SharedPreferences hiddenPrefs() {
        return act.getSharedPreferences("continue_hidden", Activity.MODE_PRIVATE);
    }

    private static String hiddenKey(WatchNextReader.Item it) {
        return it.pkg + "|" + it.title + "|" + it.episodeTitle;
    }

    private boolean isHidden(WatchNextReader.Item it) {
        android.content.SharedPreferences p = hiddenPrefs();
        String k = hiddenKey(it);
        return p.contains(k) && it.engagedAt <= p.getLong(k, 0);
    }

    /** Takes a title off the shelf. */
    void remove(WatchNextReader.Item it) {
        hiddenPrefs().edit().putLong(hiddenKey(it), Math.max(it.engagedAt, System.currentTimeMillis() > 0 ? it.engagedAt : 0)).apply();
        refocusAfterLoad = true;
        loading = false;
        refresh();
    }

    private boolean refocusAfterLoad;

    boolean isVisible() {
        return section.getVisibility() == View.VISIBLE && adapter.getItemCount() > 0;
    }

    /** Puts the shelf's bottom edge at {@code bottomY} in root coordinates. */
    void placeAbove(float bottomY) {
        section.setY(Math.max(0, bottomY - section.getHeight()));
    }

    int height() {
        return section.getHeight();
    }

    /** Focuses the card last focused, or the first. */
    boolean focus() {
        if (!isVisible()) return false;
        int at = Math.min(lastFocused, adapter.getItemCount() - 1);
        RecyclerView.ViewHolder vh = list.findViewHolderForAdapterPosition(at);
        if (vh == null) {
            list.scrollToPosition(at);
            list.post(() -> {
                RecyclerView.ViewHolder v = list.findViewHolderForAdapterPosition(at);
                if (v != null) v.itemView.requestFocus();
            });
            nav.entered();
            return true;
        }
        nav.entered();
        return vh.itemView.requestFocus();
    }

    /** Reads Watch Next again, off the main thread; the shelf hides itself when there is nothing to show. */
    void refresh() {
        if (loading) return;
        loading = true;
        new Thread(() -> {
            List<WatchNextReader.Item> rows = WatchNextReader.read(act.getApplicationContext());
            List<WatchNextReader.Item> shown = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (WatchNextReader.Item it : rows) {
                // Continue (0) and next episode (1); "new" promotions and watchlist rows are not what was being watched.
                if (it.type != 0 && it.type != 1) continue;
                if (it.progress() >= 0.95f) continue;   // effectively finished
                if (isHidden(it)) continue;             // removed from the shelf with the long-press menu
                if (!seen.add(it.pkg + "|" + it.title + "|" + it.episodeTitle)) continue;
                shown.add(it);
                if (shown.size() >= MAX_ITEMS) break;
            }
            main.post(() -> {
                loading = false;
                if (act.isDestroyed()) return;
                loadedOnce = true;
                adapter.set(shown);
                section.setVisibility(shown.isEmpty() ? View.GONE : View.VISIBLE);
                if (refocusAfterLoad) {
                    refocusAfterLoad = false;
                    if (!shown.isEmpty()) {
                        lastFocused = Math.min(lastFocused, shown.size() - 1);
                        list.post(ContinueWatchingRow.this::focus);
                    }
                }
                if (listener != null) {
                    int focusIndex = list.hasFocus() && !shown.isEmpty() ? Math.min(lastFocused, shown.size() - 1) : -1;
                    listener.onItems(shown, focusIndex);
                }
            });
        }, "continue-watching").start();
    }

    // ---- launching -------------------------------------------------------------------------------

    private void launch(WatchNextReader.Item it) {
        try {
            Intent i = Intent.parseUri(it.intentUri, Intent.URI_INTENT_SCHEME);
            if (i.getComponent() == null && i.getPackage() == null) i.setPackage(it.pkg);
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
            return;
        } catch (Exception ignored) {
            // Fall through: open the app itself.
        }
        PackageManager pm = act.getPackageManager();
        Intent app = pm.getLeanbackLaunchIntentForPackage(it.pkg);
        if (app == null) app = pm.getLaunchIntentForPackage(it.pkg);
        try {
            if (app != null) act.startActivity(app);
            else Toast.makeText(act, it.title, Toast.LENGTH_SHORT).show();
        } catch (RuntimeException e) {
            Toast.makeText(act, it.title, Toast.LENGTH_SHORT).show();
        }
    }

    // ---- cards -----------------------------------------------------------------------------------

    private final class CardAdapter extends RecyclerView.Adapter<CardHolder> {
        private final List<WatchNextReader.Item> items = new ArrayList<>();

        /** Replaces the cards, but only redraws when something changed, so a refresh does not disturb focus. */
        void set(List<WatchNextReader.Item> next) {
            boolean same = next.size() == items.size();
            for (int i = 0; same && i < next.size(); i++) {
                WatchNextReader.Item a = items.get(i), b = next.get(i);
                same = eq(a.pkg, b.pkg) && eq(a.intentUri, b.intentUri) && eq(a.title, b.title) && eq(a.episodeTitle, b.episodeTitle)
                        && a.position == b.position && a.duration == b.duration;
            }
            if (same) return;
            items.clear();
            items.addAll(next);
            notifyDataSetChanged();
        }

        private boolean eq(String a, String b) {
            return a == null ? b == null : a.equals(b);
        }

        @NonNull
        @Override
        public CardHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            return new CardHolder(buildCard());
        }

        @Override
        public void onBindViewHolder(@NonNull CardHolder h, int position) {
            WatchNextReader.Item it = items.get(position);
            h.bind(it, position);
        }

        WatchNextReader.Item itemAt(int pos) {
            return pos >= 0 && pos < items.size() ? items.get(pos) : null;
        }

        @Override
        public int getItemCount() {
            return items.size();
        }
    }

    private int dp(float v) {
        return Math.round(v * density);
    }

    /** A card is built like an app tile: the same background, corners, banner and progress bar, nothing else. */
    private FrameLayout buildCard() {
        // A cell a column wide with the card centred in it, as in the app grid.
        FrameLayout cell = new FrameLayout(act);
        cell.setLayoutParams(new RecyclerView.LayoutParams(cellW, posterH));
        cell.setClipChildren(false);
        cell.setClipToPadding(false);
        FrameLayout card = new FrameLayout(act);
        card.setId(R.id.card);   // Home keeps its glass highlight while focus is on a "card"
        cell.addView(card, new FrameLayout.LayoutParams(cardW, posterH, Gravity.CENTER_HORIZONTAL | Gravity.TOP));
        card.setFocusable(true);
        card.setClickable(true);
        card.setBackgroundResource(R.drawable.tile_bg);
        card.setClipChildren(true);
        card.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View v, Outline o) {
                o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), AppAdapter.cornerRadius(v.getWidth(), density));
            }
        });
        card.setClipToOutline(true);

        ImageView poster = new ImageView(act);
        poster.setScaleType(ImageView.ScaleType.CENTER_CROP);
        card.addView(poster, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // A scrim under the progress bar so it reads on any poster.
        View scrim = new View(act);
        scrim.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x00000000, 0xB0000000}));
        card.addView(scrim, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(36), Gravity.BOTTOM));

        View track = new View(act);
        track.setBackgroundColor(0x55FFFFFF);
        card.addView(track, new FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3), Gravity.BOTTOM));
        View fill = new View(act);
        fill.setBackgroundColor(0xFFFFFFFF);
        card.addView(fill, new FrameLayout.LayoutParams(0, dp(3), Gravity.BOTTOM));

        cell.setTag(new Views(poster, fill, card));
        return cell;
    }

    private static final class Views {
        final ImageView poster;
        final View fill, card;
        /** The glass accent for this card (its poster's vivid colour), 0 until the poster is in. */
        int accent;

        Views(ImageView poster, View fill, View card) {
            this.poster = poster;
            this.fill = fill;
            this.card = card;
        }
    }

    private final class CardHolder extends RecyclerView.ViewHolder {
        private final Views v;
        private String posterKey;

        CardHolder(View card) {
            super(card);
            v = (Views) card.getTag();
        }

        void bind(WatchNextReader.Item it, int position) {
            float p = it.progress();
            ViewGroup.LayoutParams lp = v.fill.getLayoutParams();
            lp.width = p > 0 ? Math.max(dp(3), Math.round(cardW * p)) : 0;
            v.fill.setLayoutParams(lp);
            v.poster.setImageDrawable(null);
            posterKey = it.poster;
            if (it.poster != null) {
                final String key = it.poster;
                PosterLoader.load(act, key, cardW * 2, bmp -> {
                    if (bmp != null && key.equals(posterKey)) {
                        v.poster.setImageBitmap(bmp);
                        v.accent = 0;
                    }
                });
            }
            View card = v.card;
            card.setOnClickListener(x -> launch(it));
            card.setLongClickable(true);
            card.setOnLongClickListener(x -> {
                if (listener != null) listener.onLongPress(it, card);
                return true;
            });
            // The same focus animation as an app tile (AppAdapter): zoom, lift and banner zoom with the same timings.
            final ImageView banner = v.poster;
            card.setOnFocusChangeListener((view, focused) -> {
                view.animate().cancel();
                banner.animate().cancel();
                float scale = !animations ? 1f : advancedEffects ? 1.12f : 1.10f;
                float s = focused ? scale : 1f;
                if (!animations) {
                    view.setScaleX(1f);
                    view.setScaleY(1f);
                    view.setTranslationY(0f);
                    view.setTranslationZ(0f);
                    banner.setScaleX(1f);
                    banner.setScaleY(1f);
                } else if (reducedMotion) {
                    view.animate().scaleX(s).scaleY(s)
                            .translationZ(focused && advancedEffects ? 8f * density : 0f)
                            .setDuration(focused ? Motion.REDUCED_MS : 60)
                            .setInterpolator(new android.view.animation.DecelerateInterpolator())
                            .start();
                } else {
                    view.animate().scaleX(s).scaleY(s)
                            .translationZ(focused && advancedEffects ? 8f * density : 0f)
                            .setDuration(advancedEffects ? (focused ? 220 : 170) : (focused ? 260 : 180))
                            .setInterpolator(focused && !overshoot ? new android.view.animation.DecelerateInterpolator(1.5f)
                                    : new android.view.animation.OvershootInterpolator(focused ? (advancedEffects ? 0.7f : 1.6f) : 0f))
                            .withLayer().start();
                    if (advancedEffects) {
                        banner.animate().scaleX(focused ? 1.035f : 1f).scaleY(focused ? 1.035f : 1f)
                                .setDuration(280).start();
                    }
                }
                if (focused) {
                    lastFocused = getBindingAdapterPosition();
                    if (listener != null) listener.onFocused(it);
                    nav.cardFocused(view, false, accentOf(view));   // the glass highlight, as on a tile
                }
            });
            card.setOnKeyListener((x, code, event) -> {
                if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                int pos = getBindingAdapterPosition();
                switch (code) {
                    case KeyEvent.KEYCODE_DPAD_DOWN:
                        nav.down();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_UP:
                        nav.up();
                        return true;
                    case KeyEvent.KEYCODE_DPAD_LEFT:
                        return pos <= 0;
                    case KeyEvent.KEYCODE_DPAD_RIGHT:
                        return pos >= adapter.getItemCount() - 1;
                    default:
                        return false;
                }
            });
        }
    }

    private String appLabel(String pkg) {
        try {
            PackageManager pm = act.getPackageManager();
            return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString();
        } catch (Exception e) {
            return pkg;
        }
    }

    // ---- posters ---------------------------------------------------------------------------------

    /** Fetches and decodes a poster off the main thread, once per address, down-sampled to about the card's size. */
    static final class PosterLoader {
        interface Done {
            void got(Bitmap bmp);
        }

        private static final ExecutorService POOL = Executors.newFixedThreadPool(2);
        private static final Handler MAIN = new Handler(Looper.getMainLooper());
        private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(32 * 1024 * 1024) {
            @Override
            protected int sizeOf(String key, Bitmap value) {
                return value.getByteCount();
            }
        };
        private static final int MAX_BYTES = 4 * 1024 * 1024;

        /** Prefetches an image into the caches without caring about the result. */
        static void prefetch(Activity act, String url, int targetW) {
            if (url != null) load(act, url, targetW, bmp -> {});
        }

        static void load(Activity act, String url, int targetW, Done done) {
            url = url.intern();
            final String u = url;
            Bitmap hit = CACHE.get(u);
            if (hit != null) {
                done.got(hit);
                return;
            }
            POOL.execute(() -> {
                Bitmap bmp = fetch(act, u, targetW);
                if (bmp != null) CACHE.put(u, bmp);
                MAIN.post(() -> done.got(bmp));
            });
        }

        private static Bitmap fetch(Activity act, String url, int targetW) {
            byte[] data = DiskCache.get(act, url);
            if (data == null) data = download(act, url);
            if (data == null) return null;
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, o);
            int sample = 1;
            while (o.outWidth / (sample * 2) >= targetW) sample *= 2;
            o = new BitmapFactory.Options();
            o.inSampleSize = sample;
            if (targetW >= 1000) o.inPreferredConfig = Bitmap.Config.RGB_565;   // backdrops: half the memory
            return BitmapFactory.decodeByteArray(data, 0, data.length, o);
        }

        /** The image's bytes from the network (or a content:// provider), kept on disk for next time. */
        private static byte[] download(Activity act, String url) {
            byte[] data;
            try {
                Uri uri = Uri.parse(url);
                String scheme = uri.getScheme();
                InputStream in;
                HttpURLConnection c = null;
                if ("http".equals(scheme) || "https".equals(scheme)) {
                    c = (HttpURLConnection) new URL(url).openConnection();
                    c.setConnectTimeout(8000);
                    c.setReadTimeout(8000);
                    if (c.getResponseCode() != 200) return null;
                    in = c.getInputStream();
                } else {
                    in = act.getContentResolver().openInputStream(uri);   // content:// and android.resource://
                    if (in == null) return null;
                }
                try (InputStream is = in) {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[16 * 1024];
                    int n;
                    while ((n = is.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        if (out.size() > MAX_BYTES) return null;
                    }
                    data = out.toByteArray();
                } finally {
                    if (c != null) c.disconnect();
                }
            } catch (Exception e) {
                return null;
            }
            if (url.startsWith("http")) DiskCache.put(act, url, data);   // provider images are local already
            return data;
        }
    }
}
