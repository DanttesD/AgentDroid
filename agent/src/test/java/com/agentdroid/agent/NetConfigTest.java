package com.agentdroid.agent;

import static org.junit.Assert.*;

import java.net.Proxy;
import java.net.URL;
import org.junit.After;
import org.junit.Test;

public class NetConfigTest {
    @After public void clear() { NetConfig.set("", "", ""); }

    @Test public void parsesSchemes() {
        assertEquals(Proxy.Type.HTTP, NetConfig.parse("1.2.3.4:8080").type());
        assertEquals(Proxy.Type.HTTP, NetConfig.parse("http://h:3128").type());
        assertEquals(Proxy.Type.SOCKS, NetConfig.parse("socks5://h:1080").type());
        assertNull(NetConfig.parse(""));
        assertNull(NetConfig.parse("nonsense"));
    }

    @Test public void localAddressesGoDirect() throws Exception {
        NetConfig.set("socks5://h:1080", "", "");
        assertNull(NetConfig.proxyFor(new URL("http://127.0.0.1:11434/v1")));
        assertNull(NetConfig.proxyFor(new URL("http://192.168.1.5/x")));
        assertNotNull(NetConfig.proxyFor(new URL("https://api.anthropic.com/v1")));
    }

    @Test public void clearedProxyIsDirect() throws Exception {
        NetConfig.set("h:1", "", "");
        assertTrue(NetConfig.active());
        NetConfig.set("", "", "");
        assertFalse(NetConfig.active());
        assertNull(NetConfig.proxyFor(new URL("https://api.anthropic.com/")));
    }
}
