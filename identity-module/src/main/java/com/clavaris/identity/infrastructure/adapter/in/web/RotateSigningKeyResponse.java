package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.rotatesigningkeyfororganization.RotateSigningKeyForOrganizationResult;
import java.time.Instant;
import java.util.UUID;

public record RotateSigningKeyResponse(
    UUID organizationId, String newKid, String previousKid, Instant activeFrom) {

  public static RotateSigningKeyResponse from(final RotateSigningKeyForOrganizationResult result) {
    return new RotateSigningKeyResponse(
        result.newKey().organizationId().value(),
        result.newKey().kid(),
        result.previousKid(),
        result.newKey().activeFrom());
  }
}
