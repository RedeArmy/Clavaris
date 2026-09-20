package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.identity.application.usecase.listoauthgrantsforaccount.OAuthGrant;
import com.clavaris.identity.application.usecase.listoauthgrantsforaccount.OAuthGrantsRepository;
import com.clavaris.identity.domain.model.AccountId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Adapts identity-module's {@link OAuthGrantsRepository} outbound port to raw SQL against {@code
 * oauth2_authorization} (TD-SEC-003) joined against client-registry-module's own {@link
 * OAuthClientRepository} — the bridge lives in {@code app}, the same reason {@link
 * AccountTokenRevokerBridge} does, plus this one specifically needs both modules at once, which
 * only {@code app} is allowed to depend on together (the module-graph's dependency rule).
 *
 * <p>{@code registered_client_id} holds the {@code OAuthClient}'s own primary-key UUID as a string
 * (confirmed against {@code OrganizationRegisteredClientRepository#toRegisteredClient}'s own {@code
 * RegisteredClient.withId(client.id().toString())} call), never its {@code clientId} — so resolving
 * a display {@code clientId} means a {@link OAuthClientRepository#findById(UUID)} lookup, not
 * {@code findByClientId}.
 */
@Component
class OAuthGrantsRepositoryBridge implements OAuthGrantsRepository {

  private static final String SELECT_GRANTS_SQL =
      "select id, registered_client_id, authorization_grant_type, authorized_scopes, "
          + "access_token_issued_at, refresh_token_issued_at, "
          + "access_token_expires_at, refresh_token_expires_at "
          + "from oauth2_authorization where principal_name = ?";

  private final JdbcTemplate jdbcTemplate;
  private final OAuthClientRepository oauthClients;

  /* package */ OAuthGrantsRepositoryBridge(
      final JdbcTemplate jdbcTemplate, final OAuthClientRepository oauthClients) {
    this.jdbcTemplate = jdbcTemplate;
    this.oauthClients = oauthClients;
  }

  @Override
  public List<OAuthGrant> findByAccountId(final AccountId accountId) {
    return jdbcTemplate.query(
        SELECT_GRANTS_SQL, (rs, rowNum) -> toGrant(rs), accountId.value().toString());
  }

  @Override
  public boolean revokeById(final AccountId accountId, final String authorizationId) {
    final int deleted =
        jdbcTemplate.update(
            "delete from oauth2_authorization where id = ? and principal_name = ?",
            authorizationId,
            accountId.value().toString());
    return deleted > 0;
  }

  // PMD.LongVariable: "registered_client_id" is the exact oauth2_authorization column term
  // (TD-SEC-003's own upstream schema), same precedent as OAuthClient's own
  // postLogoutRedirectUris suppression.
  @SuppressWarnings("PMD.LongVariable")
  private OAuthGrant toGrant(final ResultSet resultSet) throws SQLException {
    final String registeredClientId = resultSet.getString("registered_client_id");
    return new OAuthGrant(
        resultSet.getString("id"),
        resolveClientId(registeredClientId),
        resultSet.getString("authorization_grant_type"),
        resultSet.getString("authorized_scopes"),
        coalesceInstant(resultSet, "access_token_issued_at", "refresh_token_issued_at"),
        coalesceInstant(resultSet, "access_token_expires_at", "refresh_token_expires_at"));
  }

  // A dangling registered_client_id (the OAuthClient was deleted after issuing tokens still
  // within their lifetime) is a real, expected state, not a data-integrity bug — the caller
  // renders a fallback label for a null clientId rather than this failing the whole list.
  // PMD.OnlyOneReturn/LongVariable: same rationale as this class's own toGrant suppression above.
  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.LongVariable"})
  private String resolveClientId(final String registeredClientId) {
    try {
      return oauthClients
          .findById(UUID.fromString(registeredClientId))
          .map(OAuthClient::clientId)
          .orElse(null);
    } catch (final IllegalArgumentException e) {
      return null;
    }
  }

  // PMD.OnlyOneReturn: two real, distinct outcomes — the primary column's value when present, the
  // fallback column's otherwise — same rationale AccountProfileController's own identical
  // suppression documents.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private static Instant coalesceInstant(
      final ResultSet resultSet, final String primaryColumn, final String fallbackColumn)
      throws SQLException {
    final Instant primary = instantOrNull(resultSet, primaryColumn);
    if (primary != null) {
      return primary;
    }
    return instantOrNull(resultSet, fallbackColumn);
  }

  // java:S2143/PMD.ReplaceJavaUtilDate ("use java.time") don't apply here: JDBC's own
  // ResultSet#getTimestamp(String) literally returns java.sql.Timestamp — this method is the one
  // place that boundary type is allowed to exist, immediately converted to java.time below, same
  // "a local variable, not an inline NOSONAR" convention RefreshTokenRotationAuthenticationProvider
  // 's own identical suppression already establishes.
  @SuppressWarnings({"java:S2143", "PMD.ReplaceJavaUtilDate"})
  private static Instant instantOrNull(final ResultSet resultSet, final String column)
      throws SQLException {
    final java.sql.Timestamp value = resultSet.getTimestamp(column);
    return value == null ? null : value.toInstant();
  }
}
