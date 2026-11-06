package com.agentdroid.agent;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** The trust score shown for AI services, with the same rules as the bundled check page. */
public final class NetScore {
    private static final Set<String> RESTRICTED = new HashSet<>(Arrays.asList("CN", "HK", "MO", "RU", "KP", "IR", "SY", "CU", "BY", "VE"));

    private NetScore() { }

    /** The provider score; zero in restricted regions; -1 when unknown. */
    public static int total(int providerScore, String countryCode) {
        if (providerScore < 0 || providerScore > 100) return -1;
        if (countryCode != null && RESTRICTED.contains(countryCode.toUpperCase())) return 0;
        return providerScore;
    }

    /** 0 good, 1 medium, 2 bad, -1 unknown. */
    public static int tier(int score) {
        if (score < 0) return -1;
        return score >= 75 ? 0 : score >= 60 ? 1 : 2;
    }
}
