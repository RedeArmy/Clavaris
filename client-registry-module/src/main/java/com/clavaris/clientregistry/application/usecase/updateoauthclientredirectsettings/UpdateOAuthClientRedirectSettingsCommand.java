package com.clavaris.clientregistry.application.usecase.updateoauthclientredirectsettings;

import com.clavaris.common.domain.model.AuditActor;
import java.util.List;
import java.util.UUID;

/**
 * BR-ORG-06 (SDE-III refactor, 2026-09-23): {@code redirectUris}/{@code postLogoutRedirectUris} are
 * the only two fields an Organization owner may change after an {@code OAuthClient}'s creation —
 * {@code allowedGrantTypes}/{@code allowedScopes}/{@code requireConsent} stay creation-time-only,
 * fixed by {@code OAuthClientDefaults}. Same ownership-verification shape as {@code
 * deactivateoauthclient.DeactivateOAuthClientCommand} — {@code clientId} is this credential's own
 * primary lookup key, {@code organizationId} required (the dashboard is this command's only caller,
 * always with a real Organization context).
 *
 * <p>Replaces both lists wholesale, not a merge/diff — the dashboard's own "Redirect settings" form
 * submits the full, current set of URIs every time.
 */
// PMD.LongVariable: postLogoutRedirectUris is the exact OIDC spec term, same precedent
// RegisterOAuthClientCommand's own identical suppression documents.
@SuppressWarnings("PMD.LongVariable")
public record UpdateOAuthClientRedirectSettingsCommand(
    String clientId,
    UUID organizationId,
    List<String> redirectUris,
    List<String> postLogoutRedirectUris,
    AuditActor actor) {}
