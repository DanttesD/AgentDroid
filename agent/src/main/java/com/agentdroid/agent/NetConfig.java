package com.agentdroid.agent;

import java.net.Authenticator;
import java.net.InetSocketAddress;
import java.net.PasswordAuthentication;
import java.net.Proxy;
import java.net.URI;
import java.net.URL;

/** Optional outbound proxy for the agent's own model requests. Local and private addresses always go direct. */
public final class NetConfig {
    private static volatile Proxy proxy;
    private static volatile PasswordAuthentication auth;
    private static boolean authInstalled;

    private NetConfig() { }

    /** @param spec host:port, http://host:port or socks5://host:port; empty clears the proxy. */
    public static synchronized void set(String spec, String user, String pass) {
        proxy = parse(spec);
        auth = proxy != null && user != null && !user.isEmpty() ? new PasswordAuthentication(user, (pass == null ? "" : pass).toCharArray()) : null;
        if (!authInstalled) {
            authInstalled = true;
            Authenticator.setDefault(new Authenticator() {
                @Override protected PasswordAuthentication getPasswordAuthentication() {
                    return getRequestorType() == RequestorType.PROXY ? auth : null;
                }
            });
        }
    }

    public static boolean active() { return proxy != null; }

    static Proxy parse(String spec) {
        String s = spec == null ? "" : spec.trim();
        if (s.isEmpty()) return null;
        if (!s.contains("://")) s = "http://" + s;
        try {
            URI u = new URI(s);
            if (u.getHost() == null || u.getPort() < 1) return null;
            String scheme = u.getScheme().toLowerCase();
            Proxy.Type type = scheme.startsWith("socks") ? Proxy.Type.SOCKS : Proxy.Type.HTTP;
            return new Proxy(type, InetSocketAddress.createUnresolved(u.getHost(), u.getPort()));
        } catch (java.net.URISyntaxException e) {
            return null;
        }
    }

    static boolean isLocal(String host) {
        if (host == null) return true;
        String h = host.toLowerCase();
        return h.equals("localhost") || h.startsWith("127.") || h.equals("::1") || h.equals("[::1]") || h.startsWith("10.") || h.startsWith("192.168.")
                || h.matches("172\\.(1[6-9]|2\\d|3[01])\\..*") || h.endsWith(".local");
    }

    /** The proxy to use for this URL, or null for a direct connection. */
    static Proxy proxyFor(URL url) {
        Proxy p = proxy;
        return p == null || isLocal(url.getHost()) ? null : p;
    }
}
