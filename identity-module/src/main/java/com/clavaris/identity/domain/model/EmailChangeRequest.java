package com.clavaris.identity.domain.model;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/**
 * TD-FUT-043: a pending self-service email change — the address the account asked to move to, held
 * apart from {@link Account} until its owner proves control of it by consuming the single-use link
 * mailed there. Hash-only token, one pending request per account (a new request replaces it), same
 * BR-ID-04/BR-ID-05 single-use/time-limited posture as {@link VerificationToken}.
 */
public record EmailChangeRequest(
    UUID id, AccountId accountId, Email newEmail, String tokenHash, Instant expiresAt) {

  public EmailChangeRequest {
    Objects.requireNonNull(id, "id must not be null");
    Objects.requireNonNull(accountId, "accountId must not be null");
    Objects.requireNonNull(newEmail, "newEmail must not be null");
    Objects.requireNonNull(tokenHash, "tokenHash must not be null");
    Objects.requireNonNull(expiresAt, "expiresAt must not be null");
  }

  public static EmailChangeRequest issue(
      final AccountId accountId,
      final Email newEmail,
      final String tokenHash,
      final Instant expiresAt) {
    return new EmailChangeRequest(UUID.randomUUID(), accountId, newEmail, tokenHash, expiresAt);
  }
}
