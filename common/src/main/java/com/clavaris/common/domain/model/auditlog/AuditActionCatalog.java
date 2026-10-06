package com.clavaris.common.domain.model.auditlog;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Turns the machine names written to the audit log ({@code organization_client.deleted}) into what
 * a person reads ("Secret Key deleted"), with the group it belongs to and how it reads at a glance.
 * Every action the code base records is listed; an action added later and not yet listed degrades
 * to a readable sentence made from its own name instead of leaking the raw key, and the unit test
 * keeps this table in step with the actions the application actually writes.
 */
public final class AuditActionCatalog {

  private static final Map<String, ActionInfo> KNOWN = new HashMap<>();

  /** What a person sees for one audit action. */
  public record ActionInfo(AuditCategory category, AuditTone tone, String label) {}

  static {
    add(
        "organization.created",
        AuditCategory.ORGANIZATION,
        AuditTone.CREATED,
        "Organization created");
    add(
        "organization.deleted",
        AuditCategory.ORGANIZATION,
        AuditTone.DELETED,
        "Organization deleted");
    add(
        "organization.production_environment_created",
        AuditCategory.ORGANIZATION,
        AuditTone.CREATED,
        "Production environment created");
    add(
        "organization.social_credential_set",
        AuditCategory.ORGANIZATION,
        AuditTone.UPDATED,
        "Social login credential saved");
    add(
        "organization.social_credential_deleted",
        AuditCategory.ORGANIZATION,
        AuditTone.DELETED,
        "Social login credential removed");
    add(
        "account_authentication_policy.set",
        AuditCategory.ORGANIZATION,
        AuditTone.UPDATED,
        "Sign-in policy changed");
    add(
        "social_login_policy.set",
        AuditCategory.ORGANIZATION,
        AuditTone.UPDATED,
        "Social login settings changed");
    add(
        "rate_limit_policy.set",
        AuditCategory.ORGANIZATION,
        AuditTone.UPDATED,
        "Rate limit changed");
    add(
        "redirect_policy.set",
        AuditCategory.ORGANIZATION,
        AuditTone.UPDATED,
        "Redirect policy changed");
    add("client_branding.set", AuditCategory.ORGANIZATION, AuditTone.UPDATED, "Branding changed");
    add(
        "client_domain_config.requested",
        AuditCategory.ORGANIZATION,
        AuditTone.UPDATED,
        "Custom domain requested");
    add(
        "client_domain_config.verified",
        AuditCategory.ORGANIZATION,
        AuditTone.UPDATED,
        "Custom domain verified");
    add(
        "client_domain_config.verification_failed",
        AuditCategory.ORGANIZATION,
        AuditTone.WARNING,
        "Custom domain verification failed");
    add(
        "client_domain_config.reverified",
        AuditCategory.ORGANIZATION,
        AuditTone.UPDATED,
        "Custom domain re-verified");
    add(
        "client_domain_config.reverification_failed",
        AuditCategory.ORGANIZATION,
        AuditTone.WARNING,
        "Custom domain re-verification failed");
    add(
        "organization_client.created",
        AuditCategory.SECRET_KEYS,
        AuditTone.CREATED,
        "Secret Key created");
    add(
        "organization_client.secret_rotated",
        AuditCategory.SECRET_KEYS,
        AuditTone.SENSITIVE,
        "Secret Key's secret rotated");
    add(
        "organization_client.deactivated",
        AuditCategory.SECRET_KEYS,
        AuditTone.UPDATED,
        "Secret Key deactivated");
    add(
        "organization_client.activated",
        AuditCategory.SECRET_KEYS,
        AuditTone.UPDATED,
        "Secret Key activated");
    add(
        "organization_client.deleted",
        AuditCategory.SECRET_KEYS,
        AuditTone.DELETED,
        "Secret Key deleted");
    add(
        "oauth_client.registered",
        AuditCategory.OAUTH_CLIENTS,
        AuditTone.CREATED,
        "OAuth Client created");
    add(
        "oauth_client.activated",
        AuditCategory.OAUTH_CLIENTS,
        AuditTone.UPDATED,
        "OAuth Client reactivated");
    add(
        "oauth_client.deactivated",
        AuditCategory.OAUTH_CLIENTS,
        AuditTone.UPDATED,
        "OAuth Client deactivated");
    add(
        "oauth_client.deleted",
        AuditCategory.OAUTH_CLIENTS,
        AuditTone.DELETED,
        "OAuth Client deleted");
    add(
        "oauth_client.secret_rotated",
        AuditCategory.OAUTH_CLIENTS,
        AuditTone.SENSITIVE,
        "OAuth Client secret rotated");
    add(
        "oauth_client.consent_updated",
        AuditCategory.OAUTH_CLIENTS,
        AuditTone.UPDATED,
        "Consent screen setting changed");
    add(
        "oauth_client.grant_types_updated",
        AuditCategory.OAUTH_CLIENTS,
        AuditTone.UPDATED,
        "Grant types changed");
    add(
        "oauth_client.scopes_updated",
        AuditCategory.OAUTH_CLIENTS,
        AuditTone.UPDATED,
        "Scopes changed");
    add(
        "oauth_client.redirect_settings_updated",
        AuditCategory.OAUTH_CLIENTS,
        AuditTone.UPDATED,
        "Redirect URIs changed");
    add(
        "webhook_endpoint.registered",
        AuditCategory.WEBHOOKS,
        AuditTone.CREATED,
        "Webhook Endpoint created");
    add(
        "webhook_endpoint.url_updated",
        AuditCategory.WEBHOOKS,
        AuditTone.UPDATED,
        "Webhook URL changed");
    add(
        "webhook_endpoint.description_updated",
        AuditCategory.WEBHOOKS,
        AuditTone.UPDATED,
        "Webhook description changed");
    add(
        "webhook_endpoint.event_types_updated",
        AuditCategory.WEBHOOKS,
        AuditTone.UPDATED,
        "Webhook event types changed");
    add(
        "webhook_endpoint.secret_rotated",
        AuditCategory.WEBHOOKS,
        AuditTone.SENSITIVE,
        "Webhook signing secret rotated");
    add(
        "webhook_endpoint.activated",
        AuditCategory.WEBHOOKS,
        AuditTone.UPDATED,
        "Webhook Endpoint activated");
    add(
        "webhook_endpoint.deactivated",
        AuditCategory.WEBHOOKS,
        AuditTone.UPDATED,
        "Webhook Endpoint deactivated");
    add(
        "webhook_endpoint.deleted",
        AuditCategory.WEBHOOKS,
        AuditTone.DELETED,
        "Webhook Endpoint deleted");
    add(
        "webhook_delivery.replayed",
        AuditCategory.WEBHOOKS,
        AuditTone.UPDATED,
        "Webhook delivery replayed");
    add("workspace.created", AuditCategory.WORKSPACES, AuditTone.CREATED, "Workspace created");
    add("workspace.deleted", AuditCategory.WORKSPACES, AuditTone.DELETED, "Workspace deleted");
    add("workspace_team.created", AuditCategory.WORKSPACES, AuditTone.CREATED, "Team created");
    add("workspace_team.renamed", AuditCategory.WORKSPACES, AuditTone.UPDATED, "Team renamed");
    add("workspace_team.deleted", AuditCategory.WORKSPACES, AuditTone.DELETED, "Team deleted");
    add(
        "workspace_team.role_added",
        AuditCategory.WORKSPACES,
        AuditTone.UPDATED,
        "Role added to a team");
    add(
        "workspace_team.role_removed",
        AuditCategory.WORKSPACES,
        AuditTone.UPDATED,
        "Role removed from a team");
    add("workspace_role.created", AuditCategory.WORKSPACES, AuditTone.CREATED, "Role created");
    add("workspace_role.updated", AuditCategory.WORKSPACES, AuditTone.UPDATED, "Role updated");
    add("workspace_role.deleted", AuditCategory.WORKSPACES, AuditTone.DELETED, "Role deleted");
    add(
        "workspace_membership.added",
        AuditCategory.WORKSPACES,
        AuditTone.CREATED,
        "Member added to a workspace");
    add(
        "workspace_membership.removed",
        AuditCategory.WORKSPACES,
        AuditTone.DELETED,
        "Member removed from a workspace");
    add(
        "workspace_membership.role_changed",
        AuditCategory.WORKSPACES,
        AuditTone.UPDATED,
        "Member's role changed");
    add(
        "signing_key.rotated",
        AuditCategory.SIGNING_KEYS,
        AuditTone.SENSITIVE,
        "Signing Key rotated");
    add(
        "signing_key.emergency_purged",
        AuditCategory.SIGNING_KEYS,
        AuditTone.SENSITIVE,
        "Signing Key emergency-purged");
    add("account.banned", AuditCategory.USERS, AuditTone.SENSITIVE, "User banned");
    add("account.unbanned", AuditCategory.USERS, AuditTone.UPDATED, "User unbanned");
    add("account.suspended", AuditCategory.USERS, AuditTone.SENSITIVE, "User locked");
    add("account.reactivated", AuditCategory.USERS, AuditTone.UPDATED, "User unlocked");
    add("account.deleted", AuditCategory.USERS, AuditTone.DELETED, "User deleted");
    add(
        "account.impersonation_started",
        AuditCategory.USERS,
        AuditTone.SENSITIVE,
        "Impersonation started");
    add(
        "account.metadata_updated",
        AuditCategory.USERS,
        AuditTone.UPDATED,
        "User metadata updated");
    add(
        "account.new_device_detected",
        AuditCategory.USERS,
        AuditTone.UPDATED,
        "Sign-in from a new device");
    add(
        "account.oauth_grant_revoked",
        AuditCategory.USERS,
        AuditTone.UPDATED,
        "User's access for an app revoked");
    add(
        "account.oauth_grants_revoked_all",
        AuditCategory.USERS,
        AuditTone.UPDATED,
        "User's access for every app revoked");
    add(
        "account.password_reset_required",
        AuditCategory.USERS,
        AuditTone.UPDATED,
        "Password reset required");
    add(
        "account.permissions_updated",
        AuditCategory.USERS,
        AuditTone.UPDATED,
        "User permissions updated");
    add(
        "account.profile_picture_removed",
        AuditCategory.USERS,
        AuditTone.UPDATED,
        "Profile picture removed");
    add(
        "account.profile_picture_updated",
        AuditCategory.USERS,
        AuditTone.UPDATED,
        "Profile picture updated");
    add("account.profile_updated", AuditCategory.USERS, AuditTone.UPDATED, "User profile updated");
    add(
        "account.registration_approved",
        AuditCategory.USERS,
        AuditTone.CREATED,
        "Registration approved");
    add(
        "account.registration_rejected",
        AuditCategory.USERS,
        AuditTone.DELETED,
        "Registration rejected");
    add(
        "account.session_revoked",
        AuditCategory.USERS,
        AuditTone.UPDATED,
        "User's session revoked");
    add("webauthn_credential.deleted", AuditCategory.USERS, AuditTone.DELETED, "Passkey removed");
    add(
        "platform_account.new_device_detected",
        AuditCategory.PLATFORM,
        AuditTone.UPDATED,
        "Platform sign-in from a new device");
    add(
        "platform_account.profile_picture_removed",
        AuditCategory.PLATFORM,
        AuditTone.UPDATED,
        "Platform profile picture removed");
    add(
        "platform_account.profile_picture_updated",
        AuditCategory.PLATFORM,
        AuditTone.UPDATED,
        "Platform profile picture updated");
    add(
        "platform_account.session_revoked",
        AuditCategory.PLATFORM,
        AuditTone.UPDATED,
        "Platform session revoked");
    add(
        "platform_account.suspended",
        AuditCategory.PLATFORM,
        AuditTone.SENSITIVE,
        "Platform account suspended");
    add(
        "platform_client.deactivated",
        AuditCategory.PLATFORM,
        AuditTone.UPDATED,
        "Operator credential deactivated");
    add(
        "platform_client.rotated",
        AuditCategory.PLATFORM,
        AuditTone.SENSITIVE,
        "Operator credential rotated");
    add(
        "platform_client.secret_rotated",
        AuditCategory.PLATFORM,
        AuditTone.SENSITIVE,
        "Operator credential secret rotated");
  }

  private AuditActionCatalog() {
    // Static table only.
  }

  private static void add(
      final String action, final AuditCategory category, final AuditTone tone, final String label) {
    KNOWN.put(action, new ActionInfo(category, tone, label));
  }

  /** Every action listed in the catalog, for completeness checks. */
  public static Iterable<String> knownActions() {
    return KNOWN.keySet();
  }

  public static ActionInfo describe(final String action) {
    return Optional.ofNullable(KNOWN.get(action)).orElseGet(() -> fallback(action));
  }

  // "some_thing.was_done" -> "Some thing was done": readable, never the raw key.
  private static ActionInfo fallback(final String action) {
    final String words = action.replace('.', ' ').replace('_', ' ').strip();
    final String sentence =
        words.isEmpty()
            ? "Activity"
            : words.substring(0, 1).toUpperCase(Locale.ROOT) + words.substring(1);
    return new ActionInfo(AuditCategory.OTHER, AuditTone.UPDATED, sentence);
  }
}
