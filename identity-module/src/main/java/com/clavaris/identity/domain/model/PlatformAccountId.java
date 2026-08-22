package com.clavaris.identity.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of a {@link PlatformAccount} — a thin wrapper, not a bare {@link UUID}, same rationale
 * as {@link AccountId}: the compiler must never let a caller pass a {@code PlatformAccountId} where
 * an {@code AccountId} is expected, or vice versa. The two are deliberately unrelated types even
 * though both wrap a UUID — a {@code PlatformAccount} (ADR-0012, self-service human ownership of
 * one or more {@code Organization}s) is structurally as separate from a tenant {@code Account} as
 * {@code PlatformClient} already is from {@code OAuthClient}.
 */
public record PlatformAccountId(UUID value) {

  public PlatformAccountId {
    Objects.requireNonNull(value, "PlatformAccountId value must not be null");
  }

  /** New identity for a platform account being registered — never derived from user input. */
  public static PlatformAccountId newId() {
    return new PlatformAccountId(UUID.randomUUID());
  }
}
