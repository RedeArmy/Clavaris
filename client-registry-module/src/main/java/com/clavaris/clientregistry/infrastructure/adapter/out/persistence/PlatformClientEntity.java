package com.clavaris.clientregistry.infrastructure.adapter.out.persistence;

import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

/**
 * JPA row mapping for {@code platform_clients} (data-model.md §2). {@code allowedScopes} is stored
 * as {@code text} (JSON array) — same convention data-model.md documents for {@code
 * oauth_clients.allowed_scopes} — serialization happens in {@link JpaPlatformClientRepository}, not
 * here, same "entity is a plain persistence-mapping data holder" discipline as identity-module's
 * own JPA entities. Column mapping shared with {@link OrganizationClientEntity} via {@link
 * AbstractClientCredentialEntity} — see that class's own Javadoc for why.
 */
@SuppressWarnings("PMD.ShortVariable")
@Entity
@Table(name = "platform_clients")
public class PlatformClientEntity extends AbstractClientCredentialEntity {

  protected PlatformClientEntity() {
    super();
  }

  public PlatformClientEntity(
      final UUID id,
      final String clientId,
      final String clientSecretHash,
      final String allowedScopes,
      final Instant createdAt,
      final boolean active) {
    super(id, clientId, clientSecretHash, allowedScopes, createdAt, active);
  }
}
