package com.clavaris.app.infrastructure.config;

import com.clavaris.clientregistry.domain.model.PlatformScopes;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AuthorizeHttpRequestsConfigurer;

/**
 * Which scope each mutating {@code /api/v1/admin/**} route requires (TD-ARCH-042). Split out of
 * {@link AdminApiSecurityConfig}, whose one bean method assembled these ~40 rules together with the
 * filter chain around them; the rules are the part that grows with every new admin endpoint, and
 * can now be read and reviewed without the chain wiring in view. Same rules, same order: matching
 * is first-match, so the order is part of their meaning. Every comment moved with its rule.
 */
final class AdminApiAuthorizationRules
    implements Customizer<
        AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry> {

  private static final String SCOPE_AUTHORITY_PREFIX = "SCOPE_";

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ AdminApiAuthorizationRules() {
    // Intentionally empty — this class holds no state, only the rules.
  }

  @Override
  public void customize(
      final AuthorizeHttpRequestsConfigurer<HttpSecurity>.AuthorizationManagerRequestMatcherRegistry
          authorize) {
    authorize
        // BR-ORG-06: creating an Organization is gated by its own scope, not just
        // "any valid platform token" — defence in depth beyond BR-PLATFORM-02's
        // blanket platform-tier-only rule, matching the scope namespace
        // PlatformScopes already reserves for this exact action.
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/organizations")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.ORGANIZATIONS_WRITE)
        // SDE-III feature build, 2026-09-04 (Clerk Development/Production instances
        // analysis): promoting a DEVELOPMENT Organization by creating its PRODUCTION
        // sibling is structurally the same class of action as creating an Organization
        // in the first place — same scope, no new risk tier warranting its own.
        .requestMatchers(
            HttpMethod.POST, "/api/v1/admin/organizations/*:create-production-environment")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.ORGANIZATIONS_WRITE)
        // ADR-0010 §6.2: tuning the capacity-layer ceiling is its own scope too, same
        // defence-in-depth reasoning as Organization creation above — a platform
        // token that can create Organizations doesn't automatically get to also
        // change a running one's rate-limit ceiling.
        .requestMatchers(HttpMethod.PUT, "/api/v1/admin/organizations/*/rate-limit-policy")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.RATE_LIMIT_POLICY_WRITE)
        // Clerk "Sessions" settings parity: same defence-in-depth reasoning as the
        // rate-limit ceiling rule above — operator-managed only in v1.
        .requestMatchers(HttpMethod.PUT, "/api/v1/admin/organizations/*/session-policy")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.SESSION_POLICY_WRITE)
        // TD-SEC-008/ADR-0010 §5.2: manually-triggered key rotation is its own scope
        // too, same defence-in-depth reasoning as the two rules above.
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/organizations/*/signing-keys/rotate")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.SIGNING_KEYS_ROTATE)
        // TD-SEC-029: the emergency zero-overlap purge — its own scope, deliberately
        // separate from SIGNING_KEYS_ROTATE (see that scope's own Javadoc).
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/organizations/*/signing-keys/*:purge")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.SIGNING_KEYS_PURGE)
        // TD-SEC-018: rotating/revoking a PlatformClient — its own scopes too, same
        // defence-in-depth reasoning as every other admin-API rule above.
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/platform-clients/*/rotate-secret")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.PLATFORM_CLIENTS_ROTATE_SECRET)
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/platform-clients/*/revoke")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.PLATFORM_CLIENTS_REVOKE)
        // BR-DATA-02: hard-deleting an Account is its own scope too, same
        // defence-in-depth reasoning as every other admin-API rule above.
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/accounts/*:delete")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.ACCOUNTS_DELETE)
        // Reversible ban/unban — one shared scope for both directions, see
        // PlatformScopes.ACCOUNTS_SUSPEND's own Javadoc for why.
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/accounts/*:suspend")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.ACCOUNTS_SUSPEND)
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/accounts/*:reactivate")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.ACCOUNTS_SUSPEND)
        // TD-FUT-019: approve/reject a PENDING_APPROVAL self-registration — one
        // shared scope for both directions, see
        // PlatformScopes.ACCOUNTS_REVIEW_REGISTRATION's own Javadoc for why. Reachable
        // by an OrganizationClient too (not OPERATOR_ONLY) via
        // OrganizationClientOwnershipFilter's own allowlist for these two routes.
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/accounts/*:approve-registration")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.ACCOUNTS_REVIEW_REGISTRATION)
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/accounts/*:reject-registration")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.ACCOUNTS_REVIEW_REGISTRATION)
        // TD-FUT-034, Clerk "Metadata" parity — reachable by an OrganizationClient
        // too (not OPERATOR_ONLY), same rationale as the two routes above.
        .requestMatchers(HttpMethod.PUT, "/api/v1/admin/accounts/*/metadata")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.ACCOUNTS_METADATA_WRITE)
        // TD-FUT-041, Clerk Backend API parity — GET and PUT share one scope
        // (ACCOUNTS_PROFILE_WRITE's own Javadoc explains why), reachable by an
        // OrganizationClient too (not OPERATOR_ONLY), same rationale as the metadata
        // route right above.
        .requestMatchers(HttpMethod.GET, "/api/v1/admin/accounts/*/profile")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.ACCOUNTS_PROFILE_WRITE)
        .requestMatchers(HttpMethod.PUT, "/api/v1/admin/accounts/*/profile")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.ACCOUNTS_PROFILE_WRITE)
        // Clerk "session tasks" parity: forcing a future password reset — its own
        // scope, deliberately separate from ACCOUNTS_SUSPEND (see
        // PlatformScopes.ACCOUNTS_FORCE_PASSWORD_RESET's own Javadoc for why).
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/accounts/*:force-password-reset")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.ACCOUNTS_FORCE_PASSWORD_RESET)
        // SDE-III feature build, 2026-09-03 (Impersonation) — its own scope,
        // deliberately separate from every other admin-API scope here: see
        // PlatformScopes.ACCOUNTS_IMPERSONATE's own Javadoc for why this is the
        // single highest-impact capability this surface exposes short of the two
        // :delete endpoints.
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/accounts/*:impersonate")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.ACCOUNTS_IMPERSONATE)
        // BR-DATA-02/03: hard-deleting an entire Organization — its own scope too,
        // deliberately separate from ACCOUNTS_DELETE (an entire tenant's whole
        // account pool vs. one identity), arguably the single most irreversible
        // action this whole surface exposes.
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/organizations/*:delete")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.ORGANIZATIONS_DELETE)
        // BR-WS: creating a Workspace — its own scope, same defence-in-depth
        // reasoning as every other admin-API rule above.
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/organizations/*/workspaces")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.WORKSPACES_WRITE)
        // BR-WS-04/05: adding a member or changing an existing member's role —
        // grouped under one scope, see PlatformScopes.WORKSPACE_MEMBERS_WRITE's own
        // Javadoc for why.
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/workspaces/*/members")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.WORKSPACE_MEMBERS_WRITE)
        .requestMatchers(HttpMethod.PUT, "/api/v1/admin/workspaces/*/members/*/role")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.WORKSPACE_MEMBERS_WRITE)
        // BR-WS-03: removing a member — its own scope, deliberately separate from
        // WORKSPACE_MEMBERS_WRITE, same defence-in-depth reasoning as ACCOUNTS_DELETE.
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/workspaces/*/members/*:remove")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.WORKSPACE_MEMBERS_REMOVE)
        // TD-SEC-059: create/update/delete on WorkspaceRolesController had no
        // dedicated scope check at all before this fix, falling through to the
        // blanket .anyRequest().authenticated() below — every other mutating
        // admin-API resource here already has one.
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/organizations/*/workspace-roles")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.WORKSPACE_ROLES_WRITE)
        .requestMatchers(HttpMethod.PATCH, "/api/v1/admin/organizations/*/workspace-roles/*")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.WORKSPACE_ROLES_WRITE)
        .requestMatchers(HttpMethod.DELETE, "/api/v1/admin/organizations/*/workspace-roles/*")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.WORKSPACE_ROLES_WRITE)
        // ADR-0020 Decision 3, BR-ID-12: turning per-Organization social login on/off
        // and choosing its providers — its own scope, same defence-in-depth reasoning
        // as every other admin-API rule above.
        .requestMatchers(HttpMethod.PUT, "/api/v1/admin/organizations/*/social-login-policy")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.SOCIAL_LOGIN_POLICY_WRITE)
        // ADR-0022 (amending ADR-0020 Decision 4): setting/deleting a PRODUCTION
        // Organization's own OAuth social credentials — its own scope, deliberately
        // separate from SOCIAL_LOGIN_POLICY_WRITE (see
        // PlatformScopes.SOCIAL_CREDENTIALS
        // _WRITE's own Javadoc). Listing is read-only, no dedicated scope needed — same
        // "only mutating actions get their own scope" precedent every GET endpoint on
        // this surface already follows.
        .requestMatchers(HttpMethod.PUT, "/api/v1/admin/organizations/*/social-credentials/*")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.SOCIAL_CREDENTIALS_WRITE)
        .requestMatchers(HttpMethod.DELETE, "/api/v1/admin/organizations/*/social-credentials/*")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.SOCIAL_CREDENTIALS_WRITE)
        // ADR-0007: registering an endpoint, rotating its secret, or (de)activating it
        // — one shared scope, same "grouped under one scope for same-risk-tier actions"
        // precedent WORKSPACE_MEMBERS_WRITE already establishes.
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/organizations/*/webhook-endpoints")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.WEBHOOK_ENDPOINTS_WRITE)
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/webhook-endpoints/*:rotate-secret")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.WEBHOOK_ENDPOINTS_WRITE)
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/webhook-endpoints/*:deactivate")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.WEBHOOK_ENDPOINTS_WRITE)
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/webhook-endpoints/*:activate")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.WEBHOOK_ENDPOINTS_WRITE)
        // ADR-0007: replaying a delivery — its own scope, deliberately separate from
        // WEBHOOK_ENDPOINTS_WRITE, same defence-in-depth reasoning
        // WORKSPACE_MEMBERS_REMOVE already establishes relative to
        // WORKSPACE_MEMBERS_WRITE.
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/webhook-endpoints/*/deliveries/*:replay")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.WEBHOOK_DELIVERIES_REPLAY)
        // ADR-0023: minting a new OrganizationClient / Secret Key — its own scope, same
        // defence-in-depth reasoning as every other admin-API rule above. Listing is
        // read-only, no dedicated scope needed, same precedent as every other GET here.
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/organizations/*/secret-keys")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.SECRET_KEYS_WRITE)
        // ADR-0023: rotating/revoking a Secret Key — its own scope, deliberately
        // separate from SECRET_KEYS_WRITE, same defence-in-depth reasoning
        // WORKSPACE_MEMBERS_REMOVE already establishes relative to
        // WORKSPACE_MEMBERS_WRITE.
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/organization-clients/*/rotate-secret")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.SECRET_KEYS_ROTATE)
        .requestMatchers(HttpMethod.POST, "/api/v1/admin/organization-clients/*/revoke")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.SECRET_KEYS_ROTATE)
        // ADR-0024 (sign-up/sign-in options, Clerk parity): tuning an Organization's
        // authentication-strategy policy — its own scope, same defence-in-depth
        // reasoning as every other admin-API rule above. Reading it back (GET) is
        // unscoped, same precedent every other GET on this surface already follows.
        .requestMatchers(HttpMethod.PUT, "/api/v1/admin/organizations/*/authentication-policy")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.ACCOUNT_AUTHENTICATION_POLICY_WRITE)
        // Clerk "customize redirect URLs" parity: tuning an OAuthClient's
        // fallback/force post-authentication redirect — its own scope, same
        // defence-in-depth reasoning as every other admin-API rule above. Reading it
        // back (GET) is unscoped, same precedent every other GET on this surface
        // already follows.
        .requestMatchers(HttpMethod.PUT, "/api/v1/admin/organizations/*/clients/*/redirect-policy")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.REDIRECT_POLICY_WRITE)
        // ADR-0009 §3 (embedded/branded login, Clerk parity): tuning an OAuthClient's
        // hosted-login logo/color/display-name theming — its own scope, same
        // defence-in-depth reasoning as every other admin-API rule above. Reading it
        // back (GET) is unscoped, same precedent every other GET on this surface
        // already follows.
        .requestMatchers(HttpMethod.PUT, "/api/v1/admin/organizations/*/clients/*/branding")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.CLIENT_BRANDING_WRITE)
        // ADR-0009 §2 (embedded/branded login, Clerk parity): requesting/re-requesting
        // an OAuthClient's custom domain, and triggering its DNS TXT-record ownership
        // verification — grouped under one scope, same "same risk tier" precedent
        // WORKSPACE_MEMBERS_WRITE already establishes (both mutate one client's own
        // domain configuration; neither reaches another Organization's data).
        // Reading it back (GET) is unscoped, same precedent every other GET on this
        // surface already follows.
        .requestMatchers(HttpMethod.PUT, "/api/v1/admin/organizations/*/clients/*/domain-config")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.CLIENT_DOMAIN_WRITE)
        .requestMatchers(
            HttpMethod.POST,
            "/api/v1/admin/organizations/*/clients/*/domain-config:verify-ownership")
        .hasAuthority(SCOPE_AUTHORITY_PREFIX + PlatformScopes.CLIENT_DOMAIN_WRITE)
        .anyRequest()
        .authenticated();
  }
}
