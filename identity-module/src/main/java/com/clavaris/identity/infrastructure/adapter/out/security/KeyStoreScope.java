package com.clavaris.identity.infrastructure.adapter.out.security;

import java.util.Objects;
import java.util.UUID;

/**
 * TD-SEC-054: which signing-key-store file (and derived password) an operation targets — the
 * platform tier's own file, or one Organization's own. One file/password per scope, so a single
 * leaked file exposes only that scope's own key material, not the platform's or every other
 * Organization's too.
 *
 * <p>Package-private, only constructed by {@link OrganizationSigningKeyMaterialFactory}/{@link
 * PlatformSigningKeyMaterial} — external callers pass an {@code OrganizationId} (or nothing, for
 * the platform tier) to the factory instead.
 *
 * <p>PMD suppressions below: coding-standards.md §3a.
 */
@SuppressWarnings({
  "PMD.ShortVariable",
  "PMD.ShortMethodName",
  "PMD.AvoidFieldNameMatchingMethodName"
})
final class KeyStoreScope {

  private static final KeyStoreScope PLATFORM = new KeyStoreScope("platform");

  private final String id;

  private KeyStoreScope(final String id) {
    this.id = id;
  }

  /* package */ static KeyStoreScope platform() {
    return PLATFORM;
  }

  /* package */ static KeyStoreScope organization(final UUID organizationId) {
    return new KeyStoreScope(
        "org-" + Objects.requireNonNull(organizationId, "organizationId must not be null"));
  }

  /** Used to build both this scope's own file name and its derived password's KDF context. */
  /* package */ String id() {
    return id;
  }

  @Override
  public boolean equals(final Object other) {
    return other instanceof KeyStoreScope otherScope && id.equals(otherScope.id);
  }

  @Override
  public int hashCode() {
    return id.hashCode();
  }

  @Override
  public String toString() {
    return "KeyStoreScope[" + id + ']';
  }
}
