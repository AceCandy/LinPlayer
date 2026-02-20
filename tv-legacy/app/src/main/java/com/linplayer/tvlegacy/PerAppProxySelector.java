package com.linplayer.tvlegacy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;

final class PerAppProxySelector extends ProxySelector {
    private final Proxy upstream;
    private final ProxySelector fallback;

    PerAppProxySelector(String upstreamHost, int upstreamPort, ProxySelector fallback) {
        this.upstream = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(upstreamHost, upstreamPort));
        this.fallback = fallback;
    }

    @Override
    public List<Proxy> select(URI uri) {
        if (uri == null) return listNoProxy();
        String scheme = uri.getScheme() != null ? uri.getScheme().toLowerCase() : "";
        if (!"http".equals(scheme) && !"https".equals(scheme)) return listNoProxy();

        String host = uri.getHost() != null ? uri.getHost().trim() : "";
        if (host.isEmpty()) return listNoProxy();
        if ("localhost".equals(host) || "127.0.0.1".equals(host)) return listNoProxy();

        int[] ip = parseIpv4Literal(host);
        if (ip != null && isPrivateIpv4(ip)) return listNoProxy();

        List<Proxy> proxies = new ArrayList<>(1);
        proxies.add(upstream);
        return proxies;
    }

    @Override
    public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
        if (fallback == null) return;
        try {
            fallback.connectFailed(uri, sa, ioe);
        } catch (Exception ignored) {
            // ignore
        }
    }

    private static List<Proxy> listNoProxy() {
        List<Proxy> proxies = new ArrayList<>(1);
        proxies.add(Proxy.NO_PROXY);
        return proxies;
    }

    private static int[] parseIpv4Literal(String host) {
        String[] parts = host.split("\\.");
        if (parts.length != 4) return null;
        int[] out = new int[4];
        for (int i = 0; i < 4; i++) {
            int n;
            try {
                n = Integer.parseInt(parts[i]);
            } catch (NumberFormatException e) {
                return null;
            }
            if (n < 0 || n > 255) return null;
            out[i] = n;
        }
        return out;
    }

    private static boolean isPrivateIpv4(int[] ip) {
        int a = ip[0];
        int b = ip[1];
        if (a == 10) return true;
        if (a == 127) return true;
        if (a == 169 && b == 254) return true;
        if (a == 192 && b == 168) return true;
        return a == 172 && b >= 16 && b <= 31;
    }
}

