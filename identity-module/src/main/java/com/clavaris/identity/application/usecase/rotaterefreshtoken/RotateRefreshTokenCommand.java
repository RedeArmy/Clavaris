package com.clavaris.identity.application.usecase.rotaterefreshtoken;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

/**
 * {@code newExpiresAt} is computed by the caller, same reasoning as {@code
 * IssueRefreshTokenCommand}'s own — SAS's {@code TokenSettings} is not a concept this module
 * depends on. {@code requestedScopes} — empty means "whatever was originally authorized," matching
 * RFC 6749 §6's own default — is validated inside {@link RotateRefreshTokenService}'s single
 * transaction, before any mutation, not by the caller after the fact: see {@link
 * RequestedScopeExceedsAuthorizedScopeException}'s own Javadoc for why that ordering matters.
 */
public record RotateRefreshTokenCommand(
    String presentedRawToken, List<String> requestedScopes, Instant newExpiresAt) {

  public RotateRefreshTokenCommand {
    Objects.requireNonNull(presentedRawToken, "presentedRawToken must not be null");
    Objects.requireNonNull(requestedScopes, "requestedScopes must not be null");
    Objects.requireNonNull(newExpiresAt, "newExpiresAt must not be null");
    requestedScopes = List.copyOf(requestedScopes);
  }
}
