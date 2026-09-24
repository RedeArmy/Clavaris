package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import java.util.Arrays;
import java.util.List;

/**
 * Web-layer form object for the OAuth Client detail page's own "Redirect settings" section — the
 * one thing an Organization owner may edit after an {@code OAuthClient}'s creation (BR-ORG-06).
 * Same free-text, one-entry-per-line convention {@link RegisterOAuthClientForm}'s own identical
 * fields already established — both fields stay unbounded lists (live-verified against Clerk's own
 * OAuth Applications "Redirect URIs" field, itself a multi-entry list), not capped to one.
 */
// PMD.LongVariable: postLogoutRedirectUris is the exact OIDC spec term, same precedent as
// OAuthClient's own identical suppression.
@SuppressWarnings("PMD.LongVariable")
public class UpdateOAuthClientRedirectSettingsForm {

  private String redirectUris = "";
  private String postLogoutRedirectUris = "";

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public UpdateOAuthClientRedirectSettingsForm() {
    // Intentionally empty.
  }

  public String getRedirectUris() {
    return redirectUris;
  }

  public void setRedirectUris(final String redirectUris) {
    this.redirectUris = redirectUris == null ? "" : redirectUris;
  }

  public String getPostLogoutRedirectUris() {
    return postLogoutRedirectUris;
  }

  public void setPostLogoutRedirectUris(final String postLogoutRedirectUris) {
    this.postLogoutRedirectUris = postLogoutRedirectUris == null ? "" : postLogoutRedirectUris;
  }

  public List<String> parseRedirectUris() {
    return splitLines(redirectUris);
  }

  public List<String> parsePostLogoutRedirectUris() {
    return splitLines(postLogoutRedirectUris);
  }

  /** Populates the form's own text fields from an already-persisted client — GET, not POST. */
  public static UpdateOAuthClientRedirectSettingsForm from(
      final List<String> redirectUris, final List<String> postLogoutRedirectUris) {
    final UpdateOAuthClientRedirectSettingsForm form = new UpdateOAuthClientRedirectSettingsForm();
    form.setRedirectUris(String.join("\n", redirectUris));
    form.setPostLogoutRedirectUris(String.join("\n", postLogoutRedirectUris));
    return form;
  }

  // Same "one entry per line, trimmed, blank lines dropped" convention RegisterOAuthClientForm's
  // own identical helper already established.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private static List<String> splitLines(final String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    return Arrays.stream(raw.split("\\R"))
        .map(String::trim)
        .filter(line -> !line.isBlank())
        .toList();
  }
}
