package io.heapy.kinetica

/** Baseline response headers used by the reference HTTP servers. Does not include a content security policy. */
public val KineticaSecurityHeaders: Map<String, String> = mapOf(
    "X-Content-Type-Options" to "nosniff",
    "X-Frame-Options" to "DENY",
    "Referrer-Policy" to "no-referrer",
)
