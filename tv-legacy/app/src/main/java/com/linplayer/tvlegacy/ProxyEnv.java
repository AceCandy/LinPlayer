package com.linplayer.tvlegacy;

import java.net.ProxySelector;

final class ProxyEnv {
    private static final Object LOCK = new Object();
    private static ProxySelector original;

    private ProxyEnv() {}

    static void enable() {
        synchronized (LOCK) {
            if (original == null) {
                original = ProxySelector.getDefault();
            }
            ProxySelector.setDefault(
                    new PerAppProxySelector(
                            "127.0.0.1",
                            MihomoConfig.MIXED_PORT,
                            original));
        }
    }

    static void disable() {
        synchronized (LOCK) {
            if (original != null) {
                ProxySelector.setDefault(original);
                original = null;
            }
        }
    }
}

