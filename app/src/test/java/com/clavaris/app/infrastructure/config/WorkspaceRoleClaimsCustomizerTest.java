package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.domain.model.WorkspaceMembership;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;

/**
 * Proves the two properties that actually matter: the claim lands on both token types for a real
 * workspace member's login, and never appears at all for an Account with no membership or for a
 * grant with no end-user principal (client_credentials) — same "prove what a real token would
 * carry" discipline as {@code AuthenticationContextClaimsCustomizerTest}/{@code
 * TokenIssuanceEventLoggerTest}.
 */
class WorkspaceRoleClaimsCustomizerTest {

  private final WorkspaceMembershipRepository memberships =
      mock(WorkspaceMembershipRepository.class);
  private final WorkspaceRoleClaimsCustomizer customizer =
      new WorkspaceRoleClaimsCustomizer(memberships);

  private static JwtEncodingContext contextFor(final UUID accountId) {
    return contextFor(accountId, AuthorizationGrantType.AUTHORIZATION_CODE);
  }

  private static JwtEncodingContext contextFor(
      final UUID accountId, final AuthorizationGrantType grantType) {
    Authentication principal =
        UsernamePasswordAuthenticationToken.authenticated(accountId.toString(), null, List.of());
    JwtEncodingContext context = mock(JwtEncodingContext.class);
    when(context.getAuthorizationGrantType()).thenReturn(grantType);
    when(context.getPrincipal()).thenReturn(principal);
    return context;
  }

  @Test
  void addsWorkspaceIdAndRoleForAnAccountWithAMembership() {
    UUID accountId = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();
    when(memberships.findAllByAccountId(accountId))
        .thenReturn(List.of(WorkspaceMembership.join(workspaceId, accountId, WorkspaceRole.ADMIN)));
    JwtClaimsSet.Builder claims = JwtClaimsSet.builder();
    JwtEncodingContext context = contextFor(accountId);
    when(context.getClaims()).thenReturn(claims);

    customizer.customize(context);

    JwtClaimsSet built = claims.build();
    assertThat(built.getClaimAsString("workspace_id")).isEqualTo(workspaceId.toString());
    assertThat(built.getClaimAsString("workspace_role")).isEqualTo("ADMIN");
  }

  // Regression test: RefreshTokenRotationAuthenticationProvider reports REFRESH_TOKEN as its own
  // grant type, not AUTHORIZATION_CODE, even though it carries the same real Account principal and
  // reissues through this exact jwtGenerator (confirmed live, source-read, not assumed) — an
  // AUTHORIZATION_CODE-only guard would have silently broken BR-WS-06's own "reaches the client on
  // the next silent refresh" claim.
  @Test
  void addsWorkspaceIdAndRoleForARefreshTokenGrantToo() {
    UUID accountId = UUID.randomUUID();
    UUID workspaceId = UUID.randomUUID();
    when(memberships.findAllByAccountId(accountId))
        .thenReturn(
            List.of(WorkspaceMembership.join(workspaceId, accountId, WorkspaceRole.MEMBER)));
    JwtClaimsSet.Builder claims = JwtClaimsSet.builder();
    JwtEncodingContext context = contextFor(accountId, AuthorizationGrantType.REFRESH_TOKEN);
    when(context.getClaims()).thenReturn(claims);

    customizer.customize(context);

    JwtClaimsSet built = claims.build();
    assertThat(built.getClaimAsString("workspace_id")).isEqualTo(workspaceId.toString());
    assertThat(built.getClaimAsString("workspace_role")).isEqualTo("MEMBER");
  }

  @Test
  void addsNoClaimAtAllForAnAccountWithNoMembership() {
    UUID accountId = UUID.randomUUID();
    when(memberships.findAllByAccountId(accountId)).thenReturn(List.of());
    JwtEncodingContext context = contextFor(accountId);

    customizer.customize(context);

    // getClaims() must never even be called — same "stronger than asserting an empty claim"
    // discipline AuthenticationContextClaimsCustomizerTest's own neverTouchesAnAccessToken test
    // already established.
    verify(context, never()).getClaims();
  }

  @Test
  void neverLooksUpMembershipForAClientCredentialsGrant() {
    JwtEncodingContext context = mock(JwtEncodingContext.class);
    when(context.getAuthorizationGrantType()).thenReturn(AuthorizationGrantType.CLIENT_CREDENTIALS);

    customizer.customize(context);

    verifyNoInteractions(memberships);
  }

  @Test
  void treatsANonUuidPrincipalNameAsNoMembership_neverThrows() {
    Authentication malformedPrincipal =
        UsernamePasswordAuthenticationToken.authenticated("not-a-uuid-client-id", null, List.of());
    JwtEncodingContext context = mock(JwtEncodingContext.class);
    when(context.getAuthorizationGrantType()).thenReturn(AuthorizationGrantType.AUTHORIZATION_CODE);
    when(context.getPrincipal()).thenReturn(malformedPrincipal);

    customizer.customize(context);

    verifyNoInteractions(memberships);
  }
}
