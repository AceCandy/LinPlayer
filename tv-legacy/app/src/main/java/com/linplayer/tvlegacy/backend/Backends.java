package com.linplayer.tvlegacy.backend;

import android.content.Context;

public final class Backends {
    private static final Object LOCK = new Object();
    private static MediaBackend media;

    private Backends() {}

    public static MediaBackend media(Context context) {
        if (context == null) throw new IllegalArgumentException("context == null");
        synchronized (LOCK) {
            if (media == null) {
                media = new DemoMediaBackend();
            }
            return media;
        }
    }
}
