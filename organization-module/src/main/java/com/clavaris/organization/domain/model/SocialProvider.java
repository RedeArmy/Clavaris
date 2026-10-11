package com.clavaris.organization.domain.model;

/**
 * ADR-0022 (Per-Organization social OAuth credentials, amending ADR-0020 Decision 4): the providers
 * a PRODUCTION Organization may bring its own OAuth app credentials for. Deliberately this module's
 * own small enum, not identity-module's own {@code SocialProvider} — organization-module never
 * depends on identity-module (same module-independence rule {@code Organization}'s own Javadoc
 * documents for {@code allowedSocialProviders}, and {@code OrganizationEnvironmentChecker}'s own
 * "two small parallel enums bridged in app" precedent already established for a nearly identical
 * situation).
 *
 * <p>{@link #displayName} is how the provider writes its own name ("GitHub", not "Github"), for the
 * dashboard's Connections page.
 */
// PMD.AvoidFieldNameMatchingMethodName: the accessor is named like the field, the record-style
// convention every domain type here uses.
@SuppressWarnings("PMD.AvoidFieldNameMatchingMethodName")
public enum SocialProvider {
  GOOGLE("Google"),
  GITHUB("GitHub");

  private final String displayName;

  SocialProvider(final String displayName) {
    this.displayName = displayName;
  }

  public String displayName() {
    return displayName;
  }
}
