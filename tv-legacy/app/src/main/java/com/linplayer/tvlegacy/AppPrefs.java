package com.linplayer.tvlegacy;

import android.content.Context;
import android.content.SharedPreferences;

final class AppPrefs {
    private static final String PREFS = "linplayer_tv_legacy";

    private static final String KEY_SUBSCRIPTION_URL = "subscription_url";
    private static final String KEY_PROXY_ENABLED = "proxy_enabled";
    private static final String KEY_LAST_STATUS = "last_status";

    private AppPrefs() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static String getSubscriptionUrl(Context context) {
        String v = prefs(context).getString(KEY_SUBSCRIPTION_URL, "");
        return v != null ? v : "";
    }

    static void setSubscriptionUrl(Context context, String url) {
        String v = url != null ? url.trim() : "";
        prefs(context).edit().putString(KEY_SUBSCRIPTION_URL, v).apply();
    }

    static boolean isProxyEnabled(Context context) {
        return prefs(context).getBoolean(KEY_PROXY_ENABLED, false);
    }

    static void setProxyEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_PROXY_ENABLED, enabled).apply();
    }

    static String getLastStatus(Context context) {
        String v = prefs(context).getString(KEY_LAST_STATUS, "stopped");
        return v != null ? v : "stopped";
    }

    static void setLastStatus(Context context, String status) {
        String v = status != null ? status : "unknown";
        prefs(context).edit().putString(KEY_LAST_STATUS, v).apply();
    }
}

