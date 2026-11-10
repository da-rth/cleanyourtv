package com.gathin.tvhome;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Options chosen in the Settings pill's dialog. They are kept on this side and win over the theme's config.json for the
 * keys they cover, so a theme can still be pushed or reset without undoing what was picked here.
 */
final class UserPrefs {
    private static final String FILE = "launcher_user_prefs";

    private UserPrefs() {}

    private static SharedPreferences p(Context c) {
        return c.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    static boolean bool(Context c, String key, boolean theme) {
        SharedPreferences p = p(c);
        return p.contains(key) ? p.getBoolean(key, theme) : theme;
    }

    static int integer(Context c, String key, int theme) {
        SharedPreferences p = p(c);
        return p.contains(key) ? p.getInt(key, theme) : theme;
    }

    static String string(Context c, String key, String theme) {
        SharedPreferences p = p(c);
        return p.contains(key) ? p.getString(key, theme) : theme;
    }

    static void put(Context c, String key, String value) {
        p(c).edit().putString(key, value).apply();
    }

    static void put(Context c, String key, boolean value) {
        p(c).edit().putBoolean(key, value).apply();
    }

    static void put(Context c, String key, int value) {
        p(c).edit().putInt(key, value).apply();
    }
}
