package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Web-layer form object for the dashboard's own "register OAuthClient" form — separate from {@link
 * com.clavaris.clientregistry.infrastructure.adapter.in.web.RegisterOAuthClientRequest}, the REST
 * API's own DTO (same "web knows about forms, not the domain" split {@code
 * CreateOrganizationClientForm}'s own Javadoc documents).
 *
 * <p>Unlike {@code CreateOrganizationClientForm.allowedScopes} (one checkbox per {@code
 * PlatformScopes} constant, a small fixed Clavaris-owned vocabulary), {@code redirectUris}, {@code
 * allowedScopes}, and {@code postLogoutRedirectUris} here are free text — one entry per line —
 * since {@code OAuthClient}'s own domain validation deliberately leaves these caller-defined (see
 * {@code OAuthClient}'s own Javadoc on {@code requireValidScopes}: a consuming application's scopes
 * aren't something Clavaris can enumerate in advance). {@code allowedGrantTypes} is still
 * checkboxes — see {@link OAuthGrantTypeOptions}'s own Javadoc for why that one small, fixed list
 * is safe to offer as a UI convenience despite carrying no domain-level enforcement.
 *
 * <p>PMD.DataClass: the deliberate record-style form-object convention this codebase's own {@code
 * CreateOrganizationClientForm} etc. already establish. PMD.LongVariable: {@code
 * postLogoutRedirectUris} is the exact OIDC spec term (post_logout_redirect_uris) — same precedent
 * as {@code OAuthClient}'s own identical suppression.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.LongVariable"})
public class RegisterOAuthClientForm {

  @NotBlank(message = "Enter at least one redirect URI")
  private String redirectUris = "";

  @NotEmpty(message = "Select at least one grant type")
  private List<String> allowedGrantTypes = new ArrayList<>();

  // Genuinely optional at the domain layer (OAuthClient.requireValidScopes accepts an empty
  // list — a client_credentials-only client legitimately needs no scopes) — no @NotBlank here,
  // unlike redirectUris above.
  private String allowedScopes = "";

  private boolean requireConsent = true;

  private String postLogoutRedirectUris = "";

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public RegisterOAuthClientForm() {
    // Intentionally empty.
  }

  public String getRedirectUris() {
    return redirectUris;
  }

  public void setRedirectUris(final String redirectUris) {
    this.redirectUris = redirectUris == null ? "" : redirectUris;
  }

  public List<String> getAllowedGrantTypes() {
    return allowedGrantTypes;
  }

  public void setAllowedGrantTypes(final List<String> allowedGrantTypes) {
    this.allowedGrantTypes = allowedGrantTypes == null ? new ArrayList<>() : allowedGrantTypes;
  }

  public String getAllowedScopes() {
    return allowedScopes;
  }

  public void setAllowedScopes(final String allowedScopes) {
    this.allowedScopes = allowedScopes == null ? "" : allowedScopes;
  }

  public boolean isRequireConsent() {
    return requireConsent;
  }

  public void setRequireConsent(final boolean requireConsent) {
    this.requireConsent = requireConsent;
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

  public List<String> parseAllowedScopes() {
    return splitLines(allowedScopes);
  }

  public List<String> parsePostLogoutRedirectUris() {
    return splitLines(postLogoutRedirectUris);
  }

  // One entry per line, trimmed, blank lines dropped — the same "textarea as a poor man's list
  // input" convention this codebase has no earlier precedent for (every prior multi-value form
  // field was a fixed-vocabulary checkbox list instead), needed here because these three fields
  // are genuinely open-ended free text, not a small enumerable set. PMD.OnlyOneReturn: the early
  // "blank input" exit is clearer than folding it into the stream pipeline below.
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
