package com.clavaris.organization.infrastructure.adapter.in.web.auditlog;

import java.util.Arrays;
import java.util.Optional;

/**
 * The groups the Audit Log can be filtered by. Each audit action (see {@link AuditActionCatalog})
 * belongs to exactly one, named after the part of the dashboard a person would look in.
 */
public enum AuditCategory {
  ORGANIZATION("organization", "Organization settings"),
  SECRET_KEYS("secret-keys", "Secret Keys"),
  OAUTH_CLIENTS("oauth-clients", "OAuth clients"),
  WEBHOOKS("webhooks", "Webhooks"),
  WORKSPACES("workspaces", "Workspaces & roles"),
  SIGNING_KEYS("signing-keys", "Signing keys"),
  USERS("users", "Users"),
  PLATFORM("platform", "Platform"),
  OTHER("other", "Other");

  private final String urlSlug;
  private final String displayTitle;

  AuditCategory(final String slug, final String title) {
    this.urlSlug = slug;
    this.displayTitle = title;
  }

  /** The value used in the {@code ?category=} query parameter. */
  public String slug() {
    return urlSlug;
  }

  /** The name shown to people. */
  public String title() {
    return displayTitle;
  }

  public static Optional<AuditCategory> fromSlug(final String slug) {
    return Arrays.stream(values()).filter(category -> category.urlSlug.equals(slug)).findFirst();
  }
}
