package com.clavaris.app.infrastructure.adapter.out.security;

import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import com.clavaris.organization.domain.service.WorkspaceRoleHierarchy;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.security.oauth2.server.authorization.token.OAuth2TokenCustomizer;

/**
 * Adds {@code workspace_id}/{@code workspace_role}/{@code workspace_permissions} claims to a real
 * login's tokens — the mechanism a consuming application (e.g. JobSeeker) uses to know, at the
 * exact moment a user logs in, whether that user should see its own admin panel, and what it's
 * specifically allowed to do there (ADR-0027 adds {@code workspace_permissions}; {@code
 * workspace_role} used to be the fixed enum's name, now the consumer-defined {@link
 * WorkspaceRole#name()} of whichever role is assigned). Applied to **both** the ID token and the
 * access token (unlike {@link AuthenticationContextClaimsCustomizer}, which is ID-token-only).
 * {@code /userinfo} carries the same claims too, but not for free from the access token alone —
 * SAS's own default {@code /userinfo} mapper reads from the ID token and drops anything outside its
 * own standard-claim allow-list; {@code WorkspaceAwareOidcUserInfoMapper} (wired in {@code
 * OrganizationAuthorizationServerConfig}'s own {@code .oidc(...)} customizer) is what actually
 * carries these through — see its own Javadoc for the full, {@code javap}-confirmed reasoning.
 *
 * <p>Deliberately not a {@code @Component}, same reason {@link
 * AuthenticationContextClaimsCustomizer} isn't: {@link TokenIssuanceEventLogger} is already the
 * sole Spring-managed {@code OAuth2TokenCustomizer<JwtEncodingContext>} bean this codebase wires by
 * that interface type — adding a second would make that by-type injection ambiguous. Constructed
 * directly in {@code OrganizationAuthorizationServerConfig} and composed into the same one-{@code
 * JwtGenerator} -customizer-slot lambda every other token-issuance concern already shares.
 *
 * <p>Applies to both {@link AuthorizationGrantType#AUTHORIZATION_CODE} and {@link
 * AuthorizationGrantType#REFRESH_TOKEN} — confirmed live ({@code
 * RefreshTokenRotationAuthenticationProvider}'s own {@code DefaultOAuth2TokenContext} construction,
 * read directly from source, not assumed) that a refresh-token exchange reports {@code
 * REFRESH_TOKEN} as its grant type, not {@code AUTHORIZATION_CODE}, even though it carries the same
 * real Account principal (({@code accountId.toString()}) and reissues through this exact {@code
 * jwtGenerator}. Excluding {@code REFRESH_TOKEN} here would have silently broken this class's own
 * "a role change also reaches the client on the next silent refresh" claim (BR-WS-06) — caught
 * before it shipped, not discovered later as a support ticket. {@code client_credentials} has no
 * end-user {@code Account} principal to look a Workspace membership up for at all, so it's the only
 * grant type this customizer skips.
 *
 * <p>No claim at all — not a {@code null}/empty value — when the Account has no Workspace
 * membership, which is true for the overwhelming majority of Accounts (every one never provisioned
 * via {@code AddWorkspaceMemberService}). {@code workspace_role}/{@code workspace_permissions} are
 * likewise omitted (only {@code workspace_id} is added) when the membership itself has no role
 * assigned (ADR-0027 §5, {@code roleId == null}) — an explicitly allowed state, not an error.
 */
// PMD.LongVariable: hasAnAccountPrincipal names exactly what it is, not an organically long name
// that should shrink. PMD.OnlyOneReturn: customize() below has several genuinely independent early
// exits (no account principal, malformed principal name, no Workspace membership, no role
// assigned) — same "each needs its own exit" rationale RegisterAccountController's own identical
// suppression documents.
@SuppressWarnings({"PMD.LongVariable", "PMD.OnlyOneReturn"})
public class WorkspaceRoleClaimsCustomizer implements OAuth2TokenCustomizer<JwtEncodingContext> {

  private final WorkspaceMembershipRepository memberships;
  private final WorkspaceRoleRepository roles;

  public WorkspaceRoleClaimsCustomizer(
      final WorkspaceMembershipRepository memberships, final WorkspaceRoleRepository roles) {
    this.memberships = memberships;
    this.roles = roles;
  }

  // PMD.LawOfDemeter: context.getClaims()/getPrincipal() is the standard SAS API shape for reading
  // and customizing a token's own claim set from within an OAuth2TokenCustomizer — same "there is
  // no other way to reach it" reasoning as TokenIssuanceEventLogger's own identical suppression.
  @SuppressWarnings("PMD.LawOfDemeter")
  @Override
  public void customize(final JwtEncodingContext context) {
    final AuthorizationGrantType grantType = context.getAuthorizationGrantType();
    final boolean hasAnAccountPrincipal =
        AuthorizationGrantType.AUTHORIZATION_CODE.equals(grantType)
            || AuthorizationGrantType.REFRESH_TOKEN.equals(grantType);
    if (!hasAnAccountPrincipal) {
      return;
    }

    final Authentication principal = context.getPrincipal();
    final UUID accountId;
    try {
      accountId = UUID.fromString(principal.getName());
    } catch (final IllegalArgumentException _) {
      // A principal name that isn't a UUID can't be an accountId — same "malformed input surfaces
      // as absent, never an exception" convention as CurrentPlatformAccountResolverBridge's own
      // identical guard. Unnamed pattern: same AddWorkspaceMemberController's own precedent —
      // nothing in this catch block ever needs the exception itself.
      return;
    }

    // v1 structural invariant (AddWorkspaceMemberService's own Javadoc): an Account can only ever
    // belong to one Workspace today, since every add-member call provisions a brand-new Account
    // and no flow attaches an existing one to a second Workspace — the first result is correct,
    // not a data-loss shortcut, for as long as that invariant holds.
    final List<WorkspaceMembership> found = memberships.findAllByAccountId(accountId);
    if (found.isEmpty()) {
      return;
    }
    final WorkspaceMembership membership = found.get(0);

    context.getClaims().claim("workspace_id", membership.workspaceId().toString());

    final UUID roleId = membership.roleId();
    if (roleId == null) {
      return;
    }
    final WorkspaceRole role = roles.findById(roleId).orElse(null);
    if (role == null) {
      // Defensive only — a membership's own roleId is validated against the same Organization's
      // role set at write time (ChangeWorkspaceMemberRoleService/AddWorkspaceMemberService); this
      // guards a token-issuance read against a role deleted between that write and this read,
      // never expected to trigger in practice.
      return;
    }

    final Map<UUID, WorkspaceRole> rolesById =
        roles.findAllByOrganizationId(role.organizationId()).stream()
            .collect(Collectors.toMap(WorkspaceRole::id, Function.identity()));

    // ArrayList, deliberately not List.copyOf()'s immutable result: SAS's own JDBC-backed
    // OAuth2Authorization persistence round-trips every claim through Jackson polymorphic
    // typing (BasicPolymorphicTypeValidator) to store/restore this token's metadata — confirmed
    // live, a real 400 on /userinfo ("Could not resolve type id
    // 'java.util.ImmutableCollections$List12'... denied resolution") the moment this claim held
    // an immutable JDK collection type SAS's own allowlist doesn't recognize. A plain ArrayList
    // is on that allowlist.
    context
        .getClaims()
        .claim("workspace_role", role.name())
        .claim(
            "workspace_permissions",
            new ArrayList<>(WorkspaceRoleHierarchy.effectivePermissions(roleId, rolesById)));
  }
}
