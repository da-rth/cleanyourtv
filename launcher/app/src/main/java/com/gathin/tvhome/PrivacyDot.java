package com.gathin.tvhome;

import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

/**
 * The VPN privacy dot: a small dot drawn over every app but Home, green while a VPN is up and red while none is. It sits
 * in a corner of the screen, can fade in and out on a loop, and is set up from Launcher settings. Drawing over other apps
 * needs "Display over other apps" for the launcher (granted from a shell with
 * {@code appops set com.gathin.tvhome SYSTEM_ALERT_WINDOW allow}, or on the system page the options dialog opens).
 * {@link PrivacyDotService} keeps the process alive while the dot is on.
 */
final class PrivacyDot {
    static final String KEY_ON = "privacy_dot_v2", KEY_POSITION = "dot_position", KEY_FADE = "dot_fade", KEY_FADE_S = "dot_fade_s", KEY_SIZE = "dot_size_px", KEY_OFFSET = "dot_offset_px", KEY_STYLE = "dot_style";
    static final String[] STYLES = {"Dot", "Globe", "Lock"};
    private static final int ORANGE = 0xFFFF9F0A;
    private static final int STATE_DOWN = 0, STATE_CONNECTING = 1, STATE_UP = 2;
    static final String[] POSITIONS = {"Top right", "Top left", "Bottom right", "Bottom left"};
    private static final int GREEN = 0xFF30D158, RED = 0xFFFF453A;
    static final int DEFAULT_SIZE_PX = 24, DEFAULT_OFFSET_PX = 40;
    private static final float MIN_ALPHA = 0.15f;

    private static PrivacyDot instance;

    static synchronized PrivacyDot get(Context c) {
        if (instance == null) instance = new PrivacyDot(c.getApplicationContext());
        return instance;
    }

    static boolean enabled(Context c) {
        return UserPrefs.bool(c, KEY_ON, false);   // off until the user turns it on
    }

    static boolean canDraw(Context c) {
        return Settings.canDrawOverlays(c);
    }

    /** Starts or stops the service to match the option and the permission. Call from the launcher's activity. */
    static void sync(Context c) {
        Intent svc = new Intent(c, PrivacyDotService.class);
        if (enabled(c) && canDraw(c)) c.startForegroundService(svc);
        else c.stopService(svc);
    }

    private final Context app;
    private final WindowManager wm;
    private final Handler main = new Handler(Looper.getMainLooper());
    private View dot;
    private GradientDrawable shape;
    private Runnable fadeLoop;
    private long fadeStart;
    private boolean running, homeInFront, preview;
    private int vpnState = STATE_DOWN;
    private android.widget.ImageView icon;
    private ConnectivityManager.NetworkCallback callback;

    private PrivacyDot(Context app) {
        this.app = app;
        this.wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
    }

    void start() {
        main.post(() -> {
            if (!running) {
                running = true;
                watchVpn();
            }
            refresh();
        });
    }

    void stop() {
        main.post(() -> {
            running = false;
            unwatchVpn();
            removeDot();
        });
    }

    /** Home is in front: the dot hides (it shows over every other app). */
    void setHomeInFront(boolean inFront) {
        main.post(() -> {
            homeInFront = inFront;
            refresh();
        });
    }

    /** While the dot's settings dialog is open the dot shows over Home too, so changes can be seen. */
    void setPreview(boolean on) {
        main.post(() -> {
            preview = on;
            refresh();
        });
    }

    /** Re-reads the options (position, fade) and redraws. */
    void applyOptions() {
        main.post(this::refresh);
    }

    // ---- VPN state ---------------------------------------------------------------------------------

    /** Connected: a VPN network that Android has validated. Connecting: a VPN network that is up but not validated yet. */
    private int vpnNow() {
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        int state = STATE_DOWN;
        if (cm == null) return state;
        for (Network n : cm.getAllNetworks()) {
            NetworkCapabilities caps = cm.getNetworkCapabilities(n);
            if (caps == null || !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) continue;
            if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) return STATE_UP;
            state = STATE_CONNECTING;
        }
        return state;
    }

    private void watchVpn() {
        vpnState = vpnNow();
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null || callback != null) return;
        callback = new ConnectivityManager.NetworkCallback() {
            @Override public void onAvailable(Network network) { vpnChanged(); }
            @Override public void onLost(Network network) { vpnChanged(); }
            @Override public void onCapabilitiesChanged(Network network, NetworkCapabilities caps) { vpnChanged(); }
        };
        NetworkRequest req = new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_VPN)
                .removeCapability(NetworkCapabilities.NET_CAPABILITY_NOT_VPN).build();
        try {
            cm.registerNetworkCallback(req, callback);
        } catch (RuntimeException e) {
            callback = null;
        }
    }

    private void unwatchVpn() {
        if (callback == null) return;
        ConnectivityManager cm = (ConnectivityManager) app.getSystemService(Context.CONNECTIVITY_SERVICE);
        try {
            if (cm != null) cm.unregisterNetworkCallback(callback);
        } catch (RuntimeException ignored) {
            // Already gone.
        }
        callback = null;
    }

    private void vpnChanged() {
        main.postDelayed(() -> {
            vpnState = vpnNow();
            refresh();
        }, 300);   // the network list settles just after the callback
    }

    // ---- the dot -----------------------------------------------------------------------------------

    private void refresh() {
        if (!running || !canDraw(app)) {
            removeDot();
            return;
        }
        if (dot == null) addDot();
        if (dot == null) return;
        paint();
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) dot.getLayoutParams();
        applyPosition(lp);
        try {
            wm.updateViewLayout(dot, lp);
        } catch (RuntimeException ignored) {
            // Not attached yet.
        }
        dot.setVisibility(!homeInFront || preview ? View.VISIBLE : View.GONE);
        applyFade();
    }

    private int stateColor() {
        return vpnState == STATE_UP ? GREEN : vpnState == STATE_CONNECTING ? ORANGE : RED;
    }

    /** Draws the chosen style in the state's colour: green connected, orange connecting, red disconnected. */
    private void paint() {
        if (icon == null) return;
        int style = Math.max(0, Math.min(2, UserPrefs.integer(app, KEY_STYLE, 0)));
        int color = stateColor();
        if (style == 0) {
            shape.setColor(color);
            icon.setColorFilter(null);
            icon.setImageDrawable(shape);
        } else {
            icon.setImageResource(style == 1 ? R.drawable.ic_globe : R.drawable.ic_lock);
            icon.setColorFilter(color, android.graphics.PorterDuff.Mode.SRC_IN);
        }
    }

    private void addDot() {
        int size = sizePx();
        icon = new android.widget.ImageView(app);
        icon.setScaleType(android.widget.ImageView.ScaleType.FIT_CENTER);
        shape = new GradientDrawable();
        shape.setShape(GradientDrawable.OVAL);
        shape.setStroke(1, 0x66000000);
        View v = icon;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        applyPosition(lp);
        try {
            wm.addView(v, lp);
            dot = v;
        } catch (RuntimeException e) {
            dot = null;   // no permission after all
            icon = null;
        }
    }

    private int sizePx() {
        return Math.max(4, Math.min(200, UserPrefs.integer(app, KEY_SIZE, DEFAULT_SIZE_PX)));
    }

    private void applyPosition(WindowManager.LayoutParams lp) {
        int margin = Math.max(0, Math.min(400, UserPrefs.integer(app, KEY_OFFSET, DEFAULT_OFFSET_PX)));
        lp.width = lp.height = sizePx();
        int pos = Math.max(0, Math.min(3, UserPrefs.integer(app, KEY_POSITION, 0)));
        int vertical = pos < 2 ? Gravity.TOP : Gravity.BOTTOM;
        int horizontal = pos % 2 == 0 ? Gravity.RIGHT : Gravity.LEFT;
        lp.gravity = vertical | horizontal;
        lp.x = margin;
        lp.y = margin;
    }

    /**
     * A loop of fading out and back in: one full cycle takes the chosen number of seconds. Driven by the clock on the main
     * thread, not by an Animator: a TV with its animation scales at 0 (a developer option) makes every Animator jump to its
     * end, which froze the fade.
     */
    private void applyFade() {
        stopFade();
        if (dot == null) return;
        if (!UserPrefs.bool(app, KEY_FADE, true)) {
            dot.setAlpha(1f);
            return;
        }
        final long cycleMs = Math.max(1, UserPrefs.integer(app, KEY_FADE_S, 3)) * 1000L;
        fadeStart = android.os.SystemClock.uptimeMillis();
        fadeLoop = new Runnable() {
            @Override
            public void run() {
                if (dot == null || fadeLoop != this) return;
                float phase = ((android.os.SystemClock.uptimeMillis() - fadeStart) % cycleMs) / (float) cycleMs;   // 0..1
                float tri = phase < 0.5f ? phase * 2f : (1f - phase) * 2f;                                       // 0 -> 1 -> 0
                dot.setAlpha(1f - (1f - MIN_ALPHA) * tri);
                main.postDelayed(this, 33);
            }
        };
        fadeLoop.run();
    }

    private void stopFade() {
        if (fadeLoop != null) {
            main.removeCallbacks(fadeLoop);
            fadeLoop = null;
        }
    }

    private void removeDot() {
        stopFade();
        if (dot != null) {
            try {
                wm.removeView(dot);
            } catch (RuntimeException ignored) {
                // Already gone.
            }
            dot = null;
            icon = null;
        }
    }
}
