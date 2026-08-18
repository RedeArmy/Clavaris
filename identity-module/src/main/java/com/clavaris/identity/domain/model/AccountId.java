package com.clavaris.identity.domain.model;

import java.util.Objects;
import java.util.UUID;

/**
 * Identity of an {@link Account}. A thin wrapper, not a bare {@link UUID}, so a caller can never
 * pass an {@code OrganizationId} where an {@code AccountId} is expected — the compiler catches that
 * mistake instead of a runtime cross-tenant bug (ADR-0010).
 */
public record AccountId(UUID value) {

  public AccountId {
    Objects.requireNonNull(value, "AccountId value must not be null");
  }

  /** New identity for an account being registered — never derived from user input. */
  public static AccountId newId() {
    return new AccountId(UUID.randomUUID());
  }
}
