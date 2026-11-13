package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.domain.model.EmailVerificationMethod;
import jakarta.validation.constraints.NotNull;

/**
 * HTTP request body for {@code PUT
 * /api/v1/admin/organizations/{organizationId}/authentication-policy} (ADR-0024). Email/password
 * sign-up/sign-in themselves are deliberately absent — see {@code AccountAuthenticationPolicy}'s
 * own Javadoc for why they are permanently on, not a field here.
 */
public record SetAccountAuthenticationPolicyRequest(
    boolean emailVerificationRequiredAtSignIn,
    @NotNull EmailVerificationMethod emailVerificationMethod,
    boolean emailCodeSignInEnabled,
    boolean emailLinkSignInEnabled,
    boolean usernameRequired,
    boolean usernameSignInEnabled,
    boolean passwordAtSignUpEnabled,
    boolean deviceTrustEnabled,
    boolean selfRegistrationRequiresApproval,
    Boolean selfServiceEmailChangeEnabled,
    Boolean codeAfterPasswordRequired,
    Boolean totpEnabled,
    Boolean passkeysEnabled,
    Boolean passkeyOfferEnabled) {

  /** Absent means off: a client written before ADR-0035 must not start asking for a code. */
  public boolean codeAfterPasswordRequiredOrDefault() {
    return codeAfterPasswordRequired != null && codeAfterPasswordRequired;
  }

  /** Absent means off: a client written before ADR-0040 must not offer an authenticator app. */
  public boolean totpEnabledOrDefault() {
    return totpEnabled != null && totpEnabled;
  }

  /** Absent means on: a client written before ADR-0041 must not switch passkeys off. */
  public boolean passkeysEnabledOrDefault() {
    return passkeysEnabled == null || passkeysEnabled;
  }

  /** Absent means on: a client written before ADR-0041 must not switch the offer off. */
  public boolean passkeyOfferEnabledOrDefault() {
    return passkeyOfferEnabled == null || passkeyOfferEnabled;
  }

  /** Absent means on: a client written before TD-FUT-052 must not switch email change off. */
  public boolean selfServiceEmailChangeEnabledOrDefault() {
    return selfServiceEmailChangeEnabled == null || selfServiceEmailChangeEnabled;
  }
}
