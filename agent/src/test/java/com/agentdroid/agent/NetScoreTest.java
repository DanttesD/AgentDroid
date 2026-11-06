package com.agentdroid.agent;

import static org.junit.Assert.*;

import org.junit.Test;

public class NetScoreTest {
    @Test public void restrictedRegionsScoreZero() {
        assertEquals(0, NetScore.total(90, "RU"));
        assertEquals(0, NetScore.total(90, "cn"));
        assertEquals(55, NetScore.total(55, "NL"));
    }

    @Test public void unknownAndTiers() {
        assertEquals(-1, NetScore.total(-5, "NL"));
        assertEquals(-1, NetScore.tier(-1));
        assertEquals(0, NetScore.tier(75));
        assertEquals(1, NetScore.tier(60));
        assertEquals(2, NetScore.tier(59));
    }
}
