package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import java.util.ArrayList;
import java.util.List;

/**
 * Web-layer form object for the OAuth Client detail page's own "Redirect settings" section — the
 * one thing an Organization owner may edit after an {@code OAuthClient}'s creation (BR-ORG-06).
 *
 * <p>Live UX request, 2026-09-24: a real list UI (one input per URI, its own "Remove" button, an
 * "Add" button that appends a row) replaces this form's own original one-entry-per-line textarea —
 * matching how Clerk's own multi-entry "Redirect URIs" field actually works, per this feature's own
 * earlier Clerk-parity research. Both fields stay {@code List<String>}, bound via Spring's standard
 * indexed-property syntax ({@code redirectUris[0]}, {@code redirectUris[1]}, ...) that {@code
 * th:field="*{redirectUris[__${rowStat.index}__]}"} generates — {@link
 * PlatformOAuthClientController}'s own {@code add}/{@code remove} endpoints mutate this same list
 * in memory and re-render, never touching the domain until the form's own real "Save" submit does.
 */
// PMD.LongVariable: postLogoutRedirectUris is the exact OIDC spec term, same precedent as
// OAuthClient's own identical suppression.
@SuppressWarnings("PMD.LongVariable")
public class UpdateOAuthClientRedirectSettingsForm {

  private List<String> redirectUris = new ArrayList<>();
  private List<String> postLogoutRedirectUris = new ArrayList<>();

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public UpdateOAuthClientRedirectSettingsForm() {
    // Intentionally empty.
  }

  public List<String> getRedirectUris() {
    return redirectUris;
  }

  public void setRedirectUris(final List<String> redirectUris) {
    this.redirectUris = redirectUris == null ? new ArrayList<>() : redirectUris;
  }

  public List<String> getPostLogoutRedirectUris() {
    return postLogoutRedirectUris;
  }

  public void setPostLogoutRedirectUris(final List<String> postLogoutRedirectUris) {
    this.postLogoutRedirectUris =
        postLogoutRedirectUris == null ? new ArrayList<>() : postLogoutRedirectUris;
  }

  // Blank rows (an "Add"ed-but-never-filled-in slot, or one left empty on purpose) are dropped
  // here, at the real-save boundary — the add/remove round trips above never call this, so a
  // blank row typed in-progress is never rejected mid-edit, only once the owner actually submits.
  public List<String> parseRedirectUris() {
    return dropBlanks(redirectUris);
  }

  public List<String> parsePostLogoutRedirectUris() {
    return dropBlanks(postLogoutRedirectUris);
  }

  /**
   * Populates the form's own rows from an already-persisted client — GET, not POST. Seeds one blank
   * row when the given list is empty, same "always at least one visible input to type into"
   * convenience Clerk's own equivalent list UI gives.
   */
  public static UpdateOAuthClientRedirectSettingsForm from(
      final List<String> redirectUris, final List<String> postLogoutRedirectUris) {
    final UpdateOAuthClientRedirectSettingsForm form = new UpdateOAuthClientRedirectSettingsForm();
    form.setRedirectUris(atLeastOneBlankRow(redirectUris));
    form.setPostLogoutRedirectUris(atLeastOneBlankRow(postLogoutRedirectUris));
    return form;
  }

  private static List<String> atLeastOneBlankRow(final List<String> uris) {
    final List<String> rows = new ArrayList<>(uris);
    if (rows.isEmpty()) {
      rows.add("");
    }
    return rows;
  }

  private static List<String> dropBlanks(final List<String> uris) {
    return uris.stream().map(String::trim).filter(uri -> !uri.isBlank()).toList();
  }
}
