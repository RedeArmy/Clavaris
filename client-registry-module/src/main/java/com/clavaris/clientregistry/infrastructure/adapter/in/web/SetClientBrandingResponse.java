package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.domain.model.ClientBranding;
import java.time.Instant;
import java.util.UUID;

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
