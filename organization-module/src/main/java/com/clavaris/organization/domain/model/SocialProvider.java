package com.clavaris.organization.domain.model;

/**
 * ADR-0022 (Per-Organization social OAuth credentials, amending ADR-0020 Decision 4): the providers
 * a PRODUCTION Organization may bring its own OAuth app credentials for. Deliberately this module's
 * own small enum, not identity-module's own {@code SocialProvider} — organization-module never
 * depends on identity-module (same module-independence rule {@code Organization}'s own Javadoc
 * documents for {@code allowedSocialProviders}, and {@code OrganizationEnvironmentChecker}'s own
 * "two small parallel enums bridged in app" precedent already established for a nearly identical
 * situation).
 */
public enum SocialProvider {
  GOOGLE,
  GITHUB
}
