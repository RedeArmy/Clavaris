package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.domain.model.AccountAuthenticationPolicy;
import com.clavaris.organization.domain.model.EmailVerificationMethod;

/**
 * Every switch on an Organization's Sign-in options page, bound from one submission. An unchecked
 * box is simply absent from a form post, which binds to {@code false}, so the page always shows and
 * saves the whole set: what the operator sees is exactly what is stored, and nothing is carried
 * over from the stored policy behind their back (the page used to copy eight of these from it).
 *
 * <p>Plain getters and setters on purpose: Spring's form binding needs them, and the type is only a
 * carrier between the page and {@code PlatformSignInOptionsController}.
 */
// PMD.LongVariable: the fields carry the exact names of the policy settings they bind, which are
// already long (emailVerificationRequiredAtSignIn); a shorter alias would only hide which one is
// meant.
@SuppressWarnings("PMD.LongVariable")
public class SignInOptionsForm {

  private boolean emailVerificationRequiredAtSignIn;
  private EmailVerificationMethod emailVerificationMethod = EmailVerificationMethod.LINK;
  private boolean accessCodeEnabled;
  private boolean emailLinkSignInEnabled;
  private boolean usernameRequired;
  private boolean usernameSignInEnabled;
  private boolean passwordAtSignUpEnabled;
  private boolean deviceTrustEnabled;
  private boolean selfRegistrationRequiresApproval;
  private boolean selfServiceEmailChangeEnabled;
  private boolean codeAfterPasswordRequired;
  private boolean totpEnabled;
  private boolean passkeysEnabled;
  private boolean passkeyOfferEnabled;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public SignInOptionsForm() {
    // Intentionally empty — Spring binds the fields through the setters.
  }

  /** The form as the stored policy would fill it in. */
  public static SignInOptionsForm from(final AccountAuthenticationPolicy policy) {
    final SignInOptionsForm form = new SignInOptionsForm();
    form.emailVerificationRequiredAtSignIn = policy.emailVerificationRequiredAtSignIn();
    form.emailVerificationMethod = policy.emailVerificationMethod();
    form.accessCodeEnabled = policy.emailCodeSignInEnabled();
    form.emailLinkSignInEnabled = policy.emailLinkSignInEnabled();
    form.usernameRequired = policy.usernameRequired();
    form.usernameSignInEnabled = policy.usernameSignInEnabled();
    form.passwordAtSignUpEnabled = policy.passwordAtSignUpEnabled();
    form.deviceTrustEnabled = policy.deviceTrustEnabled();
    form.selfRegistrationRequiresApproval = policy.selfRegistrationRequiresApproval();
    form.selfServiceEmailChangeEnabled = policy.selfServiceEmailChangeEnabled();
    form.codeAfterPasswordRequired = policy.codeAfterPasswordRequired();
    form.totpEnabled = policy.totpEnabled();
    form.passkeysEnabled = policy.passkeysEnabled();
    form.passkeyOfferEnabled = policy.passkeyOfferEnabled();
    return form;
  }

  public boolean isEmailVerificationRequiredAtSignIn() {
    return emailVerificationRequiredAtSignIn;
  }

  public void setEmailVerificationRequiredAtSignIn(final boolean value) {
    this.emailVerificationRequiredAtSignIn = value;
  }

  public EmailVerificationMethod getEmailVerificationMethod() {
    return emailVerificationMethod;
  }

  public void setEmailVerificationMethod(final EmailVerificationMethod value) {
    this.emailVerificationMethod = value == null ? EmailVerificationMethod.LINK : value;
  }

  public boolean isAccessCodeEnabled() {
    return accessCodeEnabled;
  }

  public void setAccessCodeEnabled(final boolean value) {
    this.accessCodeEnabled = value;
  }

  public boolean isEmailLinkSignInEnabled() {
    return emailLinkSignInEnabled;
  }

  public void setEmailLinkSignInEnabled(final boolean value) {
    this.emailLinkSignInEnabled = value;
  }

  public boolean isUsernameRequired() {
    return usernameRequired;
  }

  public void setUsernameRequired(final boolean value) {
    this.usernameRequired = value;
  }

  public boolean isUsernameSignInEnabled() {
    return usernameSignInEnabled;
  }

  public void setUsernameSignInEnabled(final boolean value) {
    this.usernameSignInEnabled = value;
  }

  public boolean isPasswordAtSignUpEnabled() {
    return passwordAtSignUpEnabled;
  }

  public void setPasswordAtSignUpEnabled(final boolean value) {
    this.passwordAtSignUpEnabled = value;
  }

  public boolean isDeviceTrustEnabled() {
    return deviceTrustEnabled;
  }

  public void setDeviceTrustEnabled(final boolean value) {
    this.deviceTrustEnabled = value;
  }

  public boolean isSelfRegistrationRequiresApproval() {
    return selfRegistrationRequiresApproval;
  }

  public void setSelfRegistrationRequiresApproval(final boolean value) {
    this.selfRegistrationRequiresApproval = value;
  }

  public boolean isSelfServiceEmailChangeEnabled() {
    return selfServiceEmailChangeEnabled;
  }

  public void setSelfServiceEmailChangeEnabled(final boolean value) {
    this.selfServiceEmailChangeEnabled = value;
  }

  public boolean isCodeAfterPasswordRequired() {
    return codeAfterPasswordRequired;
  }

  public void setCodeAfterPasswordRequired(final boolean value) {
    this.codeAfterPasswordRequired = value;
  }

  public boolean isTotpEnabled() {
    return totpEnabled;
  }

  public void setTotpEnabled(final boolean value) {
    this.totpEnabled = value;
  }

  public boolean isPasskeysEnabled() {
    return passkeysEnabled;
  }

  public void setPasskeysEnabled(final boolean value) {
    this.passkeysEnabled = value;
  }

  public boolean isPasskeyOfferEnabled() {
    return passkeyOfferEnabled;
  }

  public void setPasskeyOfferEnabled(final boolean value) {
    this.passkeyOfferEnabled = value;
  }
}
