package com.github.tvbox.osc.util.epg;

import android.content.Context;
import android.content.SharedPreferences;

public class EpgSettings {
    private static final String PREF = "epg_settings";
    private static final String KEY_EPG_URL = "epg_url";

    public static void saveEpgUrl(Context context, String url) {
        context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .edit().putString(KEY_EPG_URL, url).apply();
    }

    public static String getEpgUrl(Context context) {
        return context.getSharedPreferences(PREF, Context.MODE_PRIVATE)
                .getString(KEY_EPG_URL, null);
    }
}
