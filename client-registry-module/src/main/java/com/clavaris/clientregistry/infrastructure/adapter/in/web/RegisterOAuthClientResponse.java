package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.RegisterOAuthClientResult;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * HTTP response body — carries {@code clientSecret} in the clear exactly once, at creation (same
 * "shown once" convention as {@code webhook_endpoints.secret_hash}, data-model.md §2). {@code
 * toString()} is overridden regardless, same defensive reason {@code RegisterOAuthClientResult}'s
 * own override exists — this is the last stop before the secret is legitimately serialized to the
 * HTTP response, not a reason to relax the "never in logs" discipline for any other path this
 * object might travel.
 */
// PMD.LongVariable: postLogoutRedirectUris is the exact OIDC spec term, not arbitrarily long —
// same precedent as OAuthClient's own identical suppression.
@SuppressWarnings({"PMD.ShortVariable", "PMD.LongVariable"})
public record RegisterOAuthClientResponse(
    UUID id,
    UUID organizationId,
    String clientId,
    String clientSecret,
    List<String> redirectUris,
    List<String> allowedGrantTypes,
    List<String> allowedScopes,
    boolean requireConsent,
    List<String> postLogoutRedirectUris,
    Instant createdAt) {

  public static RegisterOAuthClientResponse from(final RegisterOAuthClientResult result) {
    final OAuthClient client = result.client();
    return new RegisterOAuthClientResponse(
        client.id(),
        client.organizationId(),
        client.clientId(),
        result.rawClientSecret(),
        client.redirectUris(),
        client.allowedGrantTypes(),
        client.allowedScopes(),
        client.requireConsent(),
        client.postLogoutRedirectUris(),
        client.createdAt());
  }

  @Override
  public String toString() {
    return "RegisterOAuthClientResponse[id="
        + id
        + ", organizationId="
        + organizationId
        + ", clientId="
        + clientId
        + ", clientSecret=[REDACTED], redirectUris="
        + redirectUris
        + ", allowedGrantTypes="
        + allowedGrantTypes
        + ", allowedScopes="
        + allowedScopes
        + ", requireConsent="
        + requireConsent
        + ", postLogoutRedirectUris="
        + postLogoutRedirectUris
        + ", createdAt="
        + createdAt
        + ']';
  }
}
