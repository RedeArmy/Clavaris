package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.application.usecase.createproductionenvironment.CreateProductionEnvironmentResult;
import java.time.Instant;
import java.util.UUID;

/**
 * Same shape as {@code CreateOrganizationResponse}, plus the development sibling it was promoted
 * from.
 */
@SuppressWarnings({"PMD.ShortVariable", "PMD.LongVariable"})
public record CreateProductionEnvironmentResponse(
    UUID id,
    String name,
    Instant createdAt,
    UUID linkedEnvironmentOrganizationId,
    CreateOrganizationResponse.SigningKeySummary signingKey) {

  public static CreateProductionEnvironmentResponse from(
      final CreateProductionEnvironmentResult result) {
    return new CreateProductionEnvironmentResponse(
        result.organization().id(),
        result.organization().name(),
        result.organization().createdAt(),
        result.organization().linkedEnvironmentOrganizationId().orElse(null),
        new CreateOrganizationResponse.SigningKeySummary(
            result.signingKey().id(), result.signingKey().kid(), result.signingKey().algorithm()));
  }
}
