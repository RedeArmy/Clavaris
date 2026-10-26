package com.clavaris.app.infrastructure.adapter.out.persistence;

import com.clavaris.common.application.port.AuditTenantResolver;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Finds the Organization an audit event belongs to (TD-ARCH-038) by asking, for each kind of target
 * the application records events about, the table that owns it. {@code app} is the one module that
 * sees every module's tables, which is why this is here and not in {@code common}, where the
 * recorder that calls it lives. Runs inside the recording transaction, so a target created in the
 * same transaction (a Workspace just made) is visible to it.
 *
 * <p>A target that is deleted <em>before</em> its event is recorded cannot be found; callers that
 * delete and then record should pass the Organization themselves. Platform-level targets ({@code
 * PlatformAccount}, {@code PlatformClient}) and kinds without a stable owner resolve to empty.
 */
@Component
class AuditTenantResolverBridge implements AuditTenantResolver {

  private static final String UUID_SHAPE =
      "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$";

  /** Target type to the query that returns its Organization; the single parameter is the id. */
  private static final Map<String, String> QUERIES =
      Map.ofEntries(
          Map.entry("Workspace", "SELECT organization_id FROM workspaces WHERE id = ?1"),
          Map.entry("WorkspaceRole", "SELECT organization_id FROM workspace_roles WHERE id = ?1"),
          Map.entry(
              "WorkspaceTeam",
              "SELECT w.organization_id FROM workspace_teams t"
                  + " JOIN workspaces w ON w.id = t.workspace_id WHERE t.id = ?1"),
          Map.entry(
              "WorkspaceMembership",
              "SELECT w.organization_id FROM workspace_memberships m"
                  + " JOIN workspaces w ON w.id = m.workspace_id WHERE m.id = ?1"),
          Map.entry(
              "WebhookEndpoint", "SELECT organization_id FROM webhook_endpoints WHERE id = ?1"),
          Map.entry(
              "WebhookDelivery", "SELECT organization_id FROM webhook_deliveries WHERE id = ?1"),
          Map.entry("Account", "SELECT organization_id FROM accounts WHERE id = ?1"),
          Map.entry(
              "Session",
              "SELECT a.organization_id FROM sessions s"
                  + " JOIN accounts a ON a.id = s.account_id WHERE s.id = ?1"),
          Map.entry(
              "WebAuthnCredential",
              "SELECT a.organization_id FROM webauthn_credentials c"
                  + " JOIN accounts a ON a.id = c.account_id WHERE c.id = ?1"),
          Map.entry(
              "KnownDevice",
              "SELECT a.organization_id FROM known_devices k"
                  + " JOIN accounts a ON a.id = k.account_id WHERE k.id = ?1"));

  private static final String ORGANIZATION_TYPE = "Organization";
  private static final String OAUTH_CLIENT_TYPE = "OAuthClient";

  private static final String OAUTH_CLIENT_QUERY =
      "SELECT organization_id FROM oauth_clients WHERE client_id = ?1";

  // An EntityManager native query, not JdbcTemplate: Hibernate flushes pending inserts and deletes
  // before running it, so a Workspace persisted a moment ago in this same transaction is visible.
  // A JdbcTemplate query on the same connection runs ahead of Hibernate's write-behind and misses
  // it (the audit row for "workspace.created" was recorded with no Organization until this).
  private final EntityManager entityManager;

  /* package */ AuditTenantResolverBridge(final EntityManager entityManager) {
    this.entityManager = entityManager;
  }

  @Override
  public Optional<UUID> organizationOf(final String targetType, final String targetId) {
    if (targetId == null) {
      return Optional.empty();
    }
    if (ORGANIZATION_TYPE.equals(targetType)) {
      return isUuid(targetId) ? Optional.of(UUID.fromString(targetId)) : Optional.empty();
    }
    if (OAUTH_CLIENT_TYPE.equals(targetType)) {
      return first(lookup(OAUTH_CLIENT_QUERY, targetId));
    }
    final String query = QUERIES.get(targetType);
    if (query == null || !isUuid(targetId)) {
      return Optional.empty();
    }
    return first(lookup(query, UUID.fromString(targetId)));
  }

  private List<UUID> lookup(final String sql, final Object id) {
    return entityManager.createNativeQuery(sql, UUID.class).setParameter(1, id).getResultList();
  }

  private static boolean isUuid(final String value) {
    return value.matches(UUID_SHAPE);
  }

  private static Optional<UUID> first(final List<UUID> found) {
    return found.isEmpty() ? Optional.empty() : Optional.ofNullable(found.getFirst());
  }
}
