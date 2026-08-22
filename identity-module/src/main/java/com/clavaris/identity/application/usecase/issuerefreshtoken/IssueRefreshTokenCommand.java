package com.clavaris.identity.application.usecase.issuerefreshtoken;

import com.clavaris.identity.domain.model.AccountId;
import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * {@code expiresAt} is computed by the caller (the {@code app} module's SAS integration), not this
 * use case — {@code RegisteredClient.getTokenSettings().getRefreshTokenTimeToLive()} is a Spring
 * Authorization Server concept identity-module deliberately never depends on.
 */
public record IssueRefreshTokenCommand(
    AccountId accountId, List<String> authorizedScopes, Instant expiresAt) {

  public IssueRefreshTokenCommand {
    Objects.requireNonNull(accountId, "accountId must not be null");
    Objects.requireNonNull(authorizedScopes, "authorizedScopes must not be null");
    Objects.requireNonNull(expiresAt, "expiresAt must not be null");
    authorizedScopes = List.copyOf(authorizedScopes);
  }
}
