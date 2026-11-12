package com.clavaris.app.infrastructure.adapter.out.security;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.server.authorization.token.JwtEncodingContext;
import org.springframework.stereotype.Component;

/**
 * TD-ARCH-051: the claims that describe the person a token is for, as one unit. A real sign-in
 * (authorization code or refresh) and an impersonation both mint a token for an Account, and each
 * used to assemble its own list of customizers, so the impersonated token lacked the workspace
 * claims a consumer authorizes on. Both pipelines now call this class, so a claim added here
 * reaches both.
 *
 * <p>Order is the one the real pipeline always used. Each customizer decides for itself whether it
 * applies (by grant, token type and scope); this class only sequences them. Not an {@code
 * OAuth2TokenCustomizer} bean on purpose: injecting that interface by type is already ambiguous in
 * this codebase (see {@code ImpersonationTokenIssuer}'s field comment).
 */
@Component
public final class AccountClaimsCustomizers {

  private final WorkspaceRoleClaimsCustomizer workspaceRole;
  private final ProfilePictureClaimsCustomizer profilePicture;
  private final EmailClaimsCustomizer email;
  private final PhoneClaimsCustomizer phone;

  public AccountClaimsCustomizers(
      final AccountRepository accounts,
      final WorkspaceMembershipRepository workspaceMemberships,
      final WorkspaceRoleRepository workspaceRoles,
      @Value("${CLAVARIS_BASE_URL:http://localhost:8080}") final String clavarisBaseUrl) {
    this.workspaceRole = new WorkspaceRoleClaimsCustomizer(workspaceMemberships, workspaceRoles);
    this.profilePicture = new ProfilePictureClaimsCustomizer(accounts, clavarisBaseUrl);
    this.email = new EmailClaimsCustomizer(accounts);
    this.phone = new PhoneClaimsCustomizer(accounts);
  }

  public void customize(final JwtEncodingContext context) {
    workspaceRole.customize(context);
    profilePicture.customize(context);
    email.customize(context);
    phone.customize(context);
  }
}
