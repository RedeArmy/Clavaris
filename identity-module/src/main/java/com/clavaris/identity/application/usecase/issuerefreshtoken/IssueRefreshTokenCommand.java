package com.clavaris.identity.application.usecase.issuerefreshtoken;

import com.clavaris.identity.domain.model.AccountId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * {@code expiresAt} is computed by the caller (the {@code app} module's SAS integration), not this
 * use case — {@code RegisteredClient.getTokenSettings().getRefreshTokenTimeToLive()} is a Spring
 * Authorization Server concept identity-module deliberately never depends on. {@code
 * registeredClientId} is the client the token is issued to (RFC 6749 §6: only that client may
 * redeem it), carried as the opaque string the app module reads off its own {@code
 * RegisteredClient}.
 */
public record IssueRefreshTokenCommand(
    AccountId accountId,
    List<String> authorizedScopes,
    Instant expiresAt,
    Instant authenticatedAt,
    String registeredClientId) {

  /** Without a known authentication instant: the Session's own opening time stands in for it. */
  public IssueRefreshTokenCommand(
      final AccountId accountId,
      final List<String> authorizedScopes,
      final Instant expiresAt,
      final String registeredClientId) {
    this(accountId, authorizedScopes, expiresAt, null, registeredClientId);
  }

  public IssueRefreshTokenCommand {
    Objects.requireNonNull(accountId, "accountId must not be null");
    Objects.requireNonNull(authorizedScopes, "authorizedScopes must not be null");
    Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    Objects.requireNonNull(registeredClientId, "registeredClientId must not be null");
    authorizedScopes = List.copyOf(authorizedScopes);
  }
}
