package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.application.usecase.createorganization.CreateOrganizationResult;
import java.time.Instant;
import java.util.UUID;

/**
 * HTTP response body — echoes back the provisioned signing key's {@code kid}/algorithm (BR-ORG-06)
 * as proof this Organization can already issue a token, not just that a row was written. PMD's
 * ShortVariable rule flags {@code id} for the same record-style-accessor reason {@code Account}
 * (identity-module) suppresses it elsewhere in this codebase.
 */
@SuppressWarnings("PMD.ShortVariable")
public record CreateOrganizationResponse(
    UUID id, String name, Instant createdAt, SigningKeySummary signingKey) {

  public static CreateOrganizationResponse from(final CreateOrganizationResult result) {
    return new CreateOrganizationResponse(
        result.organization().id(),
        result.organization().name(),
        result.organization().createdAt(),
        new SigningKeySummary(
            result.signingKey().id(), result.signingKey().kid(), result.signingKey().algorithm()));
  }

  @SuppressWarnings("PMD.ShortVariable")
  public record SigningKeySummary(UUID id, String kid, String algorithm) {}
}
