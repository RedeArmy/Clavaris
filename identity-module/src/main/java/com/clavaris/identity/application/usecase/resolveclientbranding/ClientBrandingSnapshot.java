package com.clavaris.identity.application.usecase.resolveclientbranding;

import java.util.Optional;

/**
 * ADR-0009 §3: identity-module's own read-only view of client-registry-module's {@code
 * ClientBranding} — module independence, same "mirror the shape, never the type" rule {@code
 * AccountAuthenticationPolicySnapshot} already establishes for an identical cross-module need.
 * Every field mirrors the domain aggregate's own accessor one-to-one.
 */
// PMD.LongVariable: applicationDisplayName names exactly what ADR-0009 §3 itself calls the
// field, same ClientBranding precedent.
@SuppressWarnings("PMD.LongVariable")
public record ClientBrandingSnapshot(
    Optional<String> logoUrl,
    Optional<String> primaryColor,
    Optional<String> applicationDisplayName) {

  /** The implicit answer for a client that never configured branding, or has none at all. */
  public static ClientBrandingSnapshot unconfigured() {
    return new ClientBrandingSnapshot(Optional.empty(), Optional.empty(), Optional.empty());
  }
}
