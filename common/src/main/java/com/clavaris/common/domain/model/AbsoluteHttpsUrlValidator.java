package com.clavaris.common.domain.model;

import java.net.URI;

/**
 * TD-ARCH-019: the "well-formed, absolute, https-only URL" shape check, hand-rolled independently
 * in {@code ClientBranding.validateLogoUrl}, {@code
 * ClientDomainConfig.validateEmbeddingOriginIfPresent} (client-registry-module), and {@code
 * WebhookEndpoint.requireValidUrl} (webhook-module, across the module boundary) — each copy small
 * enough to sit under PMD CPD's own token threshold, so the duplication was invisible to the linter
 * despite being the exact same three checks hand-rolled three times. Lives here, not in either
 * business module, since no single module owns this rule — it's a pure URI-shape check with zero
 * business logic, the same "utilities, common value types" role this module already plays for
 * {@link AuditActor}/{@link AuditEvent}.
 *
 * <p>Deliberately <b>not</b> the same rule as client-registry-module's own {@code
 * OAuthClient.requireWellFormedAbsoluteSecureUri} — that validator also permits plain {@code http}
 * against {@code localhost}/{@code 127.0.0.1} for native-app loopback redirects (RFC 8252 §7.3), an
 * exception specific to OAuth redirect URIs. A logo, an iframe-embedding origin, and a webhook
 * delivery target have no such exception to make, so this class stays strict https-only rather than
 * being generalized with a flag to cover both rules.
 *
 * <p>Each caller keeps its own null/blank policy — an absent logo or embedding origin is a valid
 * "not configured" state, an absent webhook url is not — this class only ever validates a non-null
 * value the caller has already decided is worth checking, the same division of responsibility every
 * call site had before this extraction.
 */
public final class AbsoluteHttpsUrlValidator {

  private AbsoluteHttpsUrlValidator() {
    // utility class, never instantiated
  }

  /**
   * @param value the URL/origin string to validate — must not be {@code null}
   * @param fieldName used only to build a caller-specific error message
   * @return {@code value} unchanged, once proven well-formed, absolute, and https
   * @throws IllegalArgumentException if malformed, relative, or not https
   */
  public static String requireAbsoluteHttps(final String value, final String fieldName) {
    final URI parsed;
    try {
      parsed = URI.create(value);
    } catch (final IllegalArgumentException e) {
      throw new IllegalArgumentException(fieldName + " must be a well-formed URI: " + value, e);
    }
    if (!parsed.isAbsolute() || !"https".equalsIgnoreCase(parsed.getScheme())) {
      throw new IllegalArgumentException(fieldName + " must be an absolute https URL: " + value);
    }
    return value;
  }
}
