package com.clavaris.identity.infrastructure.adapter.out.security;

import java.util.Objects;
import java.util.UUID;

/**
 * TD-SEC-054: which signing-key-store file (and derived password) an operation targets — the
 * platform tier's own singleton file, or one Organization's own file. Exists so {@link
 * SigningKeyStore} never again backs every key this process ever needs with one shared file
 * protected by one shared password (see that class's own corrected Javadoc for the historical
 * design this replaces) — one file, one independently-derived password, per scope, so a single
 * leaked file (a stray backup, a support engineer's debug copy) exposes only that scope's own key
 * material, not the platform's or every other Organization's too.
 *
 * <p>Package-private and only ever constructed by {@link OrganizationSigningKeyMaterialFactory}/
 * {@link PlatformSigningKeyMaterial} — external callers never need to know this type exists, they
 * pass an {@code OrganizationId} (or nothing, for the platform tier) to the factory's own public
 * methods, which convert to a scope internally.
 *
 * <p>PMD's ShortVariable/ShortMethodName/AvoidFieldNameMatchingMethodName rules flag {@code id}
 * (field, constructor parameter, and accessor alike) for the same reason this codebase's other
 * value objects suppress them — the deliberate record-style accessor convention used throughout,
 * not an accidentally terse name.
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
