package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.purgesigningkeyfororganization.PurgeSigningKeyForOrganizationResult;
import java.time.Instant;
import java.util.UUID;

public record PurgeSigningKeyResponse(
    UUID organizationId, String purgedKid, String replacementKid, Instant purgedAt) {

  public static PurgeSigningKeyResponse from(final PurgeSigningKeyForOrganizationResult result) {
    return new PurgeSigningKeyResponse(
        result.organizationId().value(),
        result.purgedKid(),
        result.replacementKid(),
        Instant.now());
  }
}
