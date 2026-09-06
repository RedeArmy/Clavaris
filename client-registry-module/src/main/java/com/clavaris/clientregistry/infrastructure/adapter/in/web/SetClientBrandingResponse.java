package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.domain.model.ClientBranding;
import java.time.Instant;
import java.util.UUID;

// PMD.LongVariable: same SetClientBrandingCommand precedent — applicationDisplayName names
// exactly what ADR-0009 §3 itself calls the field, not arbitrarily long.
@SuppressWarnings("PMD.LongVariable")
public record SetClientBrandingResponse(
    UUID oauthClientId,
    String logoUrl,
    String primaryColor,
    String applicationDisplayName,
    Instant updatedAt) {

  public static SetClientBrandingResponse from(final ClientBranding branding) {
    return new SetClientBrandingResponse(
        branding.oauthClientId(),
        branding.logoUrl().orElse(null),
        branding.primaryColor().orElse(null),
        branding.applicationDisplayName().orElse(null),
        branding.updatedAt());
  }
}
