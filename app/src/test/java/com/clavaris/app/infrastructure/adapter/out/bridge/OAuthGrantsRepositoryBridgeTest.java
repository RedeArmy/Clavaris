package com.clavaris.app.infrastructure.adapter.out.bridge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.clientregistry.application.usecase.registeroauthclient.OAuthClientRepository;
import com.clavaris.clientregistry.domain.model.OAuthClient;
import com.clavaris.identity.application.usecase.listoauthgrantsforaccount.OAuthGrant;
import com.clavaris.identity.domain.model.AccountId;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

class OAuthGrantsRepositoryBridgeTest {

  private JdbcTemplate jdbcTemplate;
  private OAuthClientRepository oauthClients;
  private OAuthGrantsRepositoryBridge bridge;
  private AccountId accountId;

  @BeforeEach
  void setUp() {
    jdbcTemplate = mock(JdbcTemplate.class);
    oauthClients = mock(OAuthClientRepository.class);
    bridge = new OAuthGrantsRepositoryBridge(jdbcTemplate, oauthClients);
    accountId = AccountId.newId();
  }

  @Test
  void findByAccountIdQueriesByThePrincipalNameString() {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(accountId.value().toString())))
        .thenReturn(List.of());

    List<OAuthGrant> result = bridge.findByAccountId(accountId);

    assertThat(result).isEmpty();
  }

  @Test
  @SuppressWarnings("unchecked")
  void mapsARowWithAResolvableClientAndBothTimestampColumnsPresent() throws SQLException {
    UUID clientRowId = UUID.randomUUID();
    OAuthClient client = mock(OAuthClient.class);
    when(client.clientId()).thenReturn("jobseeker-web");
    when(oauthClients.findById(clientRowId)).thenReturn(Optional.of(client));

    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.getString("id")).thenReturn("auth-1");
    when(resultSet.getString("registered_client_id")).thenReturn(clientRowId.toString());
    when(resultSet.getString("authorization_grant_type")).thenReturn("authorization_code");
    when(resultSet.getString("authorized_scopes")).thenReturn("openid profile");
    Instant accessIssued = Instant.parse("2026-01-01T00:00:00Z");
    Instant accessExpires = Instant.parse("2026-01-01T01:00:00Z");
    when(resultSet.getTimestamp("access_token_issued_at")).thenReturn(Timestamp.from(accessIssued));
    when(resultSet.getTimestamp("access_token_expires_at"))
        .thenReturn(Timestamp.from(accessExpires));

    OAuthGrant grant = mapFirstRow(resultSet);

    assertThat(grant.authorizationId()).isEqualTo("auth-1");
    assertThat(grant.clientId()).isEqualTo("jobseeker-web");
    assertThat(grant.grantType()).isEqualTo("authorization_code");
    assertThat(grant.scopes()).isEqualTo("openid profile");
    assertThat(grant.lastIssuedAt()).isEqualTo(accessIssued);
    assertThat(grant.expiresAt()).isEqualTo(accessExpires);
  }

  @Test
  @SuppressWarnings("unchecked")
  void fallsBackToTheRefreshTokenTimestampsWhenTheAccessTokenColumnsAreNull() throws SQLException {
    when(oauthClients.findById(any())).thenReturn(Optional.empty());
    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.getString("id")).thenReturn("auth-2");
    when(resultSet.getString("registered_client_id")).thenReturn(UUID.randomUUID().toString());
    when(resultSet.getString("authorization_grant_type")).thenReturn("refresh_token");
    when(resultSet.getString("authorized_scopes")).thenReturn(null);
    Instant refreshIssued = Instant.parse("2026-02-01T00:00:00Z");
    Instant refreshExpires = Instant.parse("2026-03-01T00:00:00Z");
    when(resultSet.getTimestamp("access_token_issued_at")).thenReturn(null);
    when(resultSet.getTimestamp("refresh_token_issued_at"))
        .thenReturn(Timestamp.from(refreshIssued));
    when(resultSet.getTimestamp("access_token_expires_at")).thenReturn(null);
    when(resultSet.getTimestamp("refresh_token_expires_at"))
        .thenReturn(Timestamp.from(refreshExpires));

    OAuthGrant grant = mapFirstRow(resultSet);

    assertThat(grant.lastIssuedAt()).isEqualTo(refreshIssued);
    assertThat(grant.expiresAt()).isEqualTo(refreshExpires);
  }

  @Test
  @SuppressWarnings("unchecked")
  void usesNullTimestampsWhenNeitherColumnIsSet() throws SQLException {
    when(oauthClients.findById(any())).thenReturn(Optional.empty());
    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.getString("id")).thenReturn("auth-3");
    when(resultSet.getString("registered_client_id")).thenReturn(UUID.randomUUID().toString());
    when(resultSet.getString("authorization_grant_type")).thenReturn("client_credentials");
    when(resultSet.getString("authorized_scopes")).thenReturn(null);
    when(resultSet.getTimestamp("access_token_issued_at")).thenReturn(null);
    when(resultSet.getTimestamp("refresh_token_issued_at")).thenReturn(null);
    when(resultSet.getTimestamp("access_token_expires_at")).thenReturn(null);
    when(resultSet.getTimestamp("refresh_token_expires_at")).thenReturn(null);

    OAuthGrant grant = mapFirstRow(resultSet);

    assertThat(grant.lastIssuedAt()).isNull();
    assertThat(grant.expiresAt()).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void fallsBackToANullClientIdWhenRegisteredClientIdIsNotAValidUuid() throws SQLException {
    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.getString("id")).thenReturn("auth-4");
    when(resultSet.getString("registered_client_id")).thenReturn("not-a-uuid");
    when(resultSet.getString("authorization_grant_type")).thenReturn("authorization_code");
    when(resultSet.getString("authorized_scopes")).thenReturn(null);
    when(resultSet.getTimestamp(anyString())).thenReturn(null);

    OAuthGrant grant = mapFirstRow(resultSet);

    assertThat(grant.clientId()).isNull();
  }

  @Test
  @SuppressWarnings("unchecked")
  void fallsBackToANullClientIdWhenNoMatchingOAuthClientExists() throws SQLException {
    when(oauthClients.findById(any())).thenReturn(Optional.empty());
    ResultSet resultSet = mock(ResultSet.class);
    when(resultSet.getString("id")).thenReturn("auth-5");
    when(resultSet.getString("registered_client_id")).thenReturn(UUID.randomUUID().toString());
    when(resultSet.getString("authorization_grant_type")).thenReturn("authorization_code");
    when(resultSet.getString("authorized_scopes")).thenReturn(null);
    when(resultSet.getTimestamp(anyString())).thenReturn(null);

    OAuthGrant grant = mapFirstRow(resultSet);

    assertThat(grant.clientId()).isNull();
  }

  @Test
  void revokeByIdDeletesScopedToTheOwningAccountAndReturnsTrueWhenARowWasDeleted() {
    when(jdbcTemplate.update(anyString(), eq("auth-1"), eq(accountId.value().toString())))
        .thenReturn(1);

    assertThat(bridge.revokeById(accountId, "auth-1")).isTrue();
  }

  @Test
  void revokeByIdReturnsFalseWhenNoMatchingRowExists() {
    when(jdbcTemplate.update(anyString(), eq("auth-1"), eq(accountId.value().toString())))
        .thenReturn(0);

    assertThat(bridge.revokeById(accountId, "auth-1")).isFalse();
  }

  @SuppressWarnings("unchecked")
  private OAuthGrant mapFirstRow(final ResultSet resultSet) throws SQLException {
    when(jdbcTemplate.query(anyString(), any(RowMapper.class), eq(accountId.value().toString())))
        .thenReturn(List.of());
    bridge.findByAccountId(accountId);

    ArgumentCaptor<RowMapper<OAuthGrant>> rowMapperCaptor =
        ArgumentCaptor.forClass(RowMapper.class);
    verify(jdbcTemplate)
        .query(anyString(), rowMapperCaptor.capture(), eq(accountId.value().toString()));
    return rowMapperCaptor.getValue().mapRow(resultSet, 0);
  }
}
