package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.domain.model.OrganizationClient;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Deliberately excludes {@code clientSecretHash} — never round-trips out of this system, same
 * "never even the hash" posture {@code ListedOrganizationSocialCredential} already establishes.
 */
@SuppressWarnings("PMD.ShortVariable")
public record ListedOrganizationClient(
    UUID id, String clientId, List<String> allowedScopes, boolean active, Instant createdAt) {

  public static ListedOrganizationClient from(final OrganizationClient client) {
    return new ListedOrganizationClient(
        client.id(),
        client.clientId(),
        client.allowedScopes(),
        client.active(),
        client.createdAt());
  }
}
