package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.createorganizationclient.CreateOrganizationClientResult;
import com.clavaris.clientregistry.domain.model.OrganizationClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * HTTP response body — carries {@code clientSecret} in the clear exactly once, at creation, same
 * "shown once" convention as {@code RegisterOAuthClientResponse}. {@code toString()} overridden for
 * the same defensive reason.
 */
@SuppressWarnings("PMD.ShortVariable")
public record CreateOrganizationClientResponse(
    UUID id,
    UUID organizationId,
    String clientId,
    String clientSecret,
    List<String> allowedScopes,
    Instant createdAt) {

  public static CreateOrganizationClientResponse from(final CreateOrganizationClientResult result) {
    final OrganizationClient client = result.organizationClient();
    return new CreateOrganizationClientResponse(
        client.id(),
        client.organizationId(),
        client.clientId(),
        result.rawClientSecret(),
        client.allowedScopes(),
        client.createdAt());
  }

  @Override
  public String toString() {
    return "CreateOrganizationClientResponse[id="
        + id
        + ", organizationId="
        + organizationId
        + ", clientId="
        + clientId
        + ", clientSecret=[REDACTED], allowedScopes="
        + allowedScopes
        + ", createdAt="
        + createdAt
        + ']';
  }
}
