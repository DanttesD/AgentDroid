package com.agentdroid.agent

/** The trust score shown for AI services, with the same rules as the bundled check page. */
object NetScore {
    private val RESTRICTED: Set<String> = hashSetOf("CN", "HK", "MO", "RU", "KP", "IR", "SY", "CU", "BY", "VE")

    /** The provider score; zero in restricted regions; -1 when unknown. */
    @JvmStatic
    fun total(providerScore: Int, countryCode: String?): Int {
        if (providerScore < 0 || providerScore > 100) return -1
        if (countryCode != null && RESTRICTED.contains(countryCode.uppercase())) return 0
        return providerScore
    }

    /** 0 good, 1 medium, 2 bad, -1 unknown. */
    @JvmStatic
    fun tier(score: Int): Int {
        if (score < 0) return -1
        return if (score >= 75) 0 else if (score >= 60) 1 else 2
    }
}
