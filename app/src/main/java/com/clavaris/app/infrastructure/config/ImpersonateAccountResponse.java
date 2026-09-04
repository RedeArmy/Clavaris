package com.clavaris.app.infrastructure.config;

import java.time.Instant;
import java.util.Set;

/**
 * HTTP response body for {@code POST /api/v1/admin/accounts/{id}:impersonate} — a standard OAuth2
 * Bearer access-token response shape (RFC 6749 §5.1), minus {@code refresh_token}/{@code id_token}
 * (see {@code ImpersonateAccountController}'s own Javadoc for why v1 mints neither).
 */
record ImpersonateAccountResponse(
    String accessToken, String tokenType, Instant expiresAt, Set<String> scope) {

  /* package */ static ImpersonateAccountResponse from(
      final ImpersonationTokenIssuer.ImpersonationToken token) {
    return new ImpersonateAccountResponse(
        token.accessToken(), "Bearer", token.expiresAt(), token.scopes());
  }
}
