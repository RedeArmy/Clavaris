package com.clavaris.clientregistry.domain.model;

/**
 * SonarCloud duplication review, 2026-09-15: {@code clientId}/{@code clientSecretHash} each had
 * their own "must not be blank" guard, copy-pasted with only a class-specific comment across {@link
 * PlatformClient} and {@link OrganizationClient}, plus a third, independently-written copy as
 * {@code OAuthClient}'s own private {@code requireNonBlank} method — flagged as duplicated code the
 * same way {@link PlatformScopes#requireValidScopes(java.util.List)} already was for the scope-list
 * check (see that method's own Javadoc for the identical precedent). Extracted here, next to {@link
 * PlatformScopes}, for the same reason: one shared validator every credential class' constructor
 * calls, instead of one copy per caller.
 */
final class ClientCredentialFields {

  private ClientCredentialFields() {
    // Static utility — not instantiable.
  }

  /**
   * @throws IllegalArgumentException if {@code value} is {@code null} or blank
   */
  /* package */ static String requireNonBlank(final String value, final String fieldName) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(fieldName + " must not be blank");
    }
    return value;
  }
}
