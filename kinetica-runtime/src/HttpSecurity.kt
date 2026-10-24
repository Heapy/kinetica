package io.heapy.kinetica

/**
 * Response headers used by the reference HTTP servers.
 * Inline styles remain allowed because Kinetica applies layout through style attributes.
 * Scripts and network requests must use the same origin; inline executable scripts are blocked.
 */
public val KineticaSecurityHeaders: Map<String, String> = mapOf(
    "X-Content-Type-Options" to "nosniff",
    "X-Frame-Options" to "DENY",
    "Referrer-Policy" to "no-referrer",
    "Content-Security-Policy" to
        "default-src 'none'; script-src 'self'; style-src 'self' 'unsafe-inline'; " +
        "img-src 'self'; connect-src 'self'; frame-ancestors 'none'",
)
