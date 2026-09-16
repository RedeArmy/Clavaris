package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.OrganizationSocialLoginPolicyProvider;
import com.clavaris.identity.application.usecase.registeraccount.EmailAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.registeraccount.RegisterAccountCommand;
import com.clavaris.identity.application.usecase.registeraccount.RegisterAccountUseCase;
import com.clavaris.identity.application.usecase.registeraccount.UsernameAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.registeraccount.UsernameRequiredException;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.application.usecase.requestemailsignincode.RequestEmailSignInCodeCommand;
import com.clavaris.identity.application.usecase.requestemailsignincode.RequestEmailSignInCodeUseCase;
import com.clavaris.identity.application.usecase.requestemailsigninlink.RequestEmailSignInLinkCommand;
import com.clavaris.identity.application.usecase.requestemailsigninlink.RequestEmailSignInLinkUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.application.usecase.requestemailverification.MailDeliveryException;
import com.clavaris.identity.application.usecase.requestemailverification.RequestEmailVerificationCommand;
import com.clavaris.identity.application.usecase.requestemailverification.RequestEmailVerificationUseCase;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialProvider;
import com.clavaris.identity.domain.service.PasswordPolicy;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * Thymeleaf form-POST controller (server-rendered hosted UI), not a JSON API: registration happens
 * through the login/consent surface, not the management API.
 *
 * <p>{@code organizationId} comes from the path, never from a form field — the hosted UI's own
 * origin is scoped per-Organization ({@code {clavarisBaseUrl}/o/{organizationId}/...},
 * integration-design.md §1, ADR-0010 §5.1), the same way {@code /oauth2/authorize} and every other
 * endpoint under this Organization's issuer are. A user filling in an organization ID themselves
 * would be exactly the kind of tenant-boundary mistake ADR-0010 exists to make structurally
 * impossible.
 *
 * <p>ADR-0024 §4/§5: also reads the Organization's own {@code AccountAuthenticationPolicy} to
 * decide whether the username field is offered/required and whether a password is required at all —
 * when {@code passwordAtSignUpEnabled} is off, a successful registration doesn't establish a
 * session directly (there's no password credential to have proven); it instead kicks off whichever
 * passwordless email method the policy has enabled (§3), reusing those use cases entirely rather
 * than a third, duplicated completion path.
 *
 * <p>SDE-III review, 2026-09-16 — real bug found live, same fix as {@code
 * RegisterPlatformAccountController}'s own identical addendum: the password-submitted branch's
 * {@code requestEmailVerification.handle(...)} call used to run unguarded. The account is already
 * committed by the time it runs, so a Resend outage/misconfiguration threw {@link
 * MailDeliveryException} straight through this handler — an unhandled 500 on a request that had
 * already succeeded. Caught the same way {@code RecordAccountLoginDeviceService} already treats an
 * identically-shaped failure. Deliberately NOT applied to {@link #completePasswordlessSignUp} below
 * — there, the email/link send is the entire completion mechanism, not a side notification, per
 * that class's own Javadoc; the caller genuinely needs to know whether it went through.
 *
 * <p>SDE-III review, 2026-09-16 — sign-up-with-Google/GitHub buttons added: {@code
 * AuthenticateWithSocialProviderService}'s own three-way linking decision already treats a
 * first-time social login as account creation ("a brand-new signup: create both atomically and log
 * in immediately") — the exact "sign in with Google" pattern MAANG-style systems use to double as
 * "sign up with Google" for a never-before-seen identity. That flow already existed and was already
 * reachable from {@link LoginController}'s own hosted page; this page simply never linked to it.
 * {@link #addSignUpOptions} now surfaces the same {@code socialProviders} model attribute {@code
 * LoginController#addSignInOptions} does, read by {@code register.html}'s own new social-provider
 * block (a straight copy of {@code login.html}'s own — same {@link
 * SocialLoginRedirectController#forOrganization} target, same re-verification of {@link
 * OrganizationSocialLoginPolicyProvider} there before any third-party redirect, same
 * anti-enumeration posture). No new use case, no new controller — reuses the sign-in flow verbatim,
 * since signing up and signing in via a social provider are structurally the same request here.
 */
// PMD.LongVariable: requestEmailVerification/requestEmailSignInCode/requestEmailSignInLink each
// name exactly which passwordless completion path they trigger — TD-SEC-004's own original
// rationale, extended to its two new siblings, now also socialLoginPolicyProvider.
// PMD.ExcessiveImports:
// this class's own SDE-III review addendum above's MailDeliveryException/Logger/LoggerFactory
// pushed this past the default threshold of 30 — every import here backs a real, distinct
// collaborator this controller genuinely needs, same "wiring, not sprawl" reasoning this codebase
// applies elsewhere. PMD.AvoidDuplicateLiterals: the repeated string is "PMD.LongVariable" itself,
// used on several descriptively-named fields/parameters — same rationale identity-module's own
// IdentityUseCaseConfig class-level suppression documents for this exact
// PMD-annotation-string-as-literal false positive.
@SuppressWarnings({"PMD.LongVariable", "PMD.ExcessiveImports", "PMD.AvoidDuplicateLiterals"})
@Controller
@RequestMapping("/o/{organizationId}/register")
public class RegisterAccountController {

  private static final Logger LOG = LoggerFactory.getLogger(RegisterAccountController.class);

  private static final String FORM_VIEW = "identity/register";

  // Every redirect this controller issues targets this same Organization's own hosted UI — one
  // constant, not three repeated literals.
  private static final String REDIRECT_ORGANIZATION_PREFIX = "redirect:/o/";

  // SonarCloud S1192: not just a duplicate-literal fix — "email"/"username" name the same concept
  // in every one of their uses below (the bindingResult field, the redirect query param, the model
  // attribute), so one constant each is more honest about that than three/four independently-typed
  // copies that could silently drift apart (a typo in one becoming a field error Thymeleaf can no
  // longer match to the right input).
  private static final String EMAIL = "email";
  private static final String USERNAME = "username";

  private final RegisterAccountUseCase useCase;
  private final RequestEmailVerificationUseCase requestEmailVerification;
  private final AccountAuthenticationPolicyProvider policyProvider;
  private final RequestEmailSignInCodeUseCase requestEmailSignInCode;
  private final RequestEmailSignInLinkUseCase requestEmailSignInLink;

  // Same port LoginController's own identical field already uses — no new abstraction, this
  // controller just now also reads it.
  @SuppressWarnings("PMD.LongVariable")
  private final OrganizationSocialLoginPolicyProvider socialLoginPolicyProvider;

  @SuppressWarnings({"java:S107", "PMD.LongVariable"})
  public RegisterAccountController(
      final RegisterAccountUseCase useCase,
      final RequestEmailVerificationUseCase requestEmailVerification,
      final AccountAuthenticationPolicyProvider policyProvider,
      final RequestEmailSignInCodeUseCase requestEmailSignInCode,
      final RequestEmailSignInLinkUseCase requestEmailSignInLink,
      @SuppressWarnings("PMD.LongVariable")
          final OrganizationSocialLoginPolicyProvider socialLoginPolicyProvider) {
    this.useCase = useCase;
    this.requestEmailVerification = requestEmailVerification;
    this.policyProvider = policyProvider;
    this.requestEmailSignInCode = requestEmailSignInCode;
    this.requestEmailSignInLink = requestEmailSignInLink;
    this.socialLoginPolicyProvider = socialLoginPolicyProvider;
  }

  @GetMapping
  public String showForm(@PathVariable final UUID organizationId, final Model model) {
    model.addAttribute("form", new RegisterAccountForm());
    addSignUpOptions(organizationId, model);
    return FORM_VIEW;
  }

  // Early return per rejection reason is clearer here than accumulating a single exit through
  // nested branching for the several independent failure modes (validation, password required,
  // password mismatch, username required, taken email, taken username, weak password) that each
  // need their own field error — PMD.OnlyOneReturn would make this harder to follow, not easier.
  @SuppressWarnings({"PMD.OnlyOneReturn", "PMD.CyclomaticComplexity"})
  @PostMapping
  public String register(
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute("form") final RegisterAccountForm form,
      final BindingResult bindingResult,
      final Model model,
      // Clerk "customize redirect URLs" parity — see LoginController's own identical parameters.
      // Only ever consulted for the passwordless-completion redirect below: the password-submitted
      // path lands on a purely informational "check your email" page with no follow-on redirect to
      // carry them into, so there's nothing here for them to affect.
      @RequestParam(required = false) final String clientId,
      @RequestParam(required = false) final String redirectUrl) {
    final OrganizationId orgId = new OrganizationId(organizationId);
    final AccountAuthenticationPolicySnapshot policy = policyProvider.policyFor(orgId);
    if (bindingResult.hasErrors()) {
      addSignUpOptions(organizationId, model);
      return FORM_VIEW;
    }

    final boolean passwordSubmitted = form.getPassword() != null && !form.getPassword().isBlank();
    if (passwordSubmitted) {
      // Cross-field check, not expressible as a single-field Bean Validation annotation — a typo
      // in either field must be caught before a raw password is hashed and persisted from a value
      // the user didn't actually mean to set.
      if (!form.getPassword().equals(form.getConfirmPassword())) {
        bindingResult.rejectValue(
            "confirmPassword", "confirmPassword.mismatch", "Passwords do not match");
        addSignUpOptions(organizationId, model);
        return FORM_VIEW;
      }
    } else if (policy.passwordAtSignUpEnabled()) {
      bindingResult.rejectValue("password", "password.required", "Password is required");
      addSignUpOptions(organizationId, model);
      return FORM_VIEW;
    }

    final AccountId accountId;
    try {
      accountId =
          useCase.handle(
              new RegisterAccountCommand(
                  orgId, new Email(form.getEmail()), form.getPassword(), form.getUsername()));
    } catch (EmailAlreadyRegisteredException _) {
      // Never leaks the low-level exception message (which includes the raw organizationId
      // UUID) to the rendered page — a generic, field-scoped error only.
      bindingResult.rejectValue(
          EMAIL, "email.alreadyRegistered", "This email is already registered");
      addSignUpOptions(organizationId, model);
      return FORM_VIEW;
    } catch (WeakPasswordException _) {
      // SDE-III review, 2026-09-16: same rationale as ResetPasswordController's own identical
      // fix — states the actual rule instead of a vague "doesn't meet the minimum requirements".
      bindingResult.rejectValue(
          "password",
          "password.tooWeak",
          "Password must be between "
              + PasswordPolicy.MIN_LENGTH
              + " and "
              + PasswordPolicy.MAX_LENGTH
              + " characters");
      addSignUpOptions(organizationId, model);
      return FORM_VIEW;
    } catch (UsernameRequiredException _) {
      bindingResult.rejectValue(USERNAME, "username.required", "Username is required");
      addSignUpOptions(organizationId, model);
      return FORM_VIEW;
    } catch (UsernameAlreadyRegisteredException _) {
      bindingResult.rejectValue(
          USERNAME, "username.alreadyRegistered", "This username is already taken");
      addSignUpOptions(organizationId, model);
      return FORM_VIEW;
    } catch (final IllegalArgumentException _) {
      // SDE-III review, 2026-09-16: real gap found live - Username's own domain constructor
      // rejects a shape RegisterAccountForm's own size bound (max 32 characters) alone doesn't
      // catch (too short, uppercase, spaces, punctuation outside letters/digits/underscore/hyphen
      // - see that form field's own comment for why the shape check is deliberately not
      // duplicated there), and nothing here caught it: an uncaught IllegalArgumentException
      // reaching this method meant an unhandled 500 on sign-up, not a field-level message - the
      // exact registration-side gap UsernameSignInController's own identical catch already closes
      // for sign-in.
      bindingResult.rejectValue(
          USERNAME,
          "username.invalid",
          "Username must be 3-32 characters (letters, digits, underscore, hyphen only)");
      addSignUpOptions(organizationId, model);
      return FORM_VIEW;
    }

    if (!passwordSubmitted) {
      // ADR-0024 §5: no password credential the account holder actually knows — completing sign-up
      // means completing whichever passwordless method the policy enabled, reusing §3's own
      // use cases entirely rather than a third, duplicated flow.
      return completePasswordlessSignUp(organizationId, orgId, form, policy, clientId, redirectUrl);
    }

    // TD-SEC-004: this is the fix — a real send, triggered directly from the request that just
    // created the account, not left to an outbox row nothing drains yet (AccountRegisteredEvent's
    // own Javadoc documents that this is a deliberate divergence from its "async via outbox"
    // language, for exactly that reason). See this class's own Javadoc addendum for the real,
    // live-found bug the surrounding try/catch guards.
    try {
      requestEmailVerification.handle(new RequestEmailVerificationCommand(accountId));
    } catch (final MailDeliveryException e) {
      LOG.warn("event=account_registered_verification_email_send_failed", e);
    }

    // MAANG "check your email" parity (Clerk/Auth0/Okta all confirm which address, not just that
    // one was sent): same RedirectQueryParams-mediated hop as completePasswordlessSignUp's own
    // email param below — never string-concatenated directly, same header/query-injection rationale
    // RedirectQueryParams's own Javadoc documents.
    String target =
        REDIRECT_ORGANIZATION_PREFIX + organizationId + "/register/pending-verification";
    target = RedirectQueryParams.appendIfPresent(target, EMAIL, form.getEmail());
    return target;
  }

  // Two genuinely distinct exits (email-code vs. email-link completion) — same "one exit per
  // distinct outcome" rationale this codebase's own guard-clause-heavy resolution logic already
  // documents elsewhere.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private String completePasswordlessSignUp(
      final UUID organizationId,
      final OrganizationId orgId,
      final RegisterAccountForm form,
      final AccountAuthenticationPolicySnapshot policy,
      final String clientId,
      final String redirectUrl) {
    final Email email = new Email(form.getEmail());
    if (policy.emailCodeSignInEnabled()) {
      requestEmailSignInCode.handle(new RequestEmailSignInCodeCommand(orgId, email));
      // Clerk "customize redirect URLs" parity: carried into EmailCodeSignInController's own
      // confirm step, same cross-URL-redirect propagation as EmailCodeSignInController's own
      // requestCode. Resolved there as RedirectAction.SIGN_IN (that endpoint's own only mode) —
      // a client with only a SIGN_UP policy configured falls through to the platform default here,
      // a deliberate, documented simplification rather than threading a separate action flag
      // through this shared completion endpoint.
      // SDE-III review, 2026-09-15 — same real bug, same fix, as
      // EmailCodeSignInController#requestCode's own identical redirect: email is now appended via
      // RedirectQueryParams like every other param on this hop, not concatenated directly. See
      // RedirectQueryParams's own Javadoc for the header/query injection primitive this closes.
      String target = REDIRECT_ORGANIZATION_PREFIX + organizationId + "/login/email-code/confirm";
      target = RedirectQueryParams.appendIfPresent(target, EMAIL, form.getEmail());
      target = RedirectQueryParams.appendIfPresent(target, "clientId", clientId);
      target = RedirectQueryParams.appendIfPresent(target, "redirectUrl", redirectUrl);
      return target;
    }
    // SetAccountAuthenticationPolicyForOrganizationService's own validation already guarantees at
    // least one of the two is enabled whenever passwordAtSignUpEnabled is off — this is the only
    // remaining possibility, not a silently-assumed one.
    requestEmailSignInLink.handle(new RequestEmailSignInLinkCommand(orgId, email));
    // EmailLinkSignInController's own confirm step doesn't read clientId/redirectUrl at all yet
    // (deliberately deferred — see that controller's own DeviceTrustGate.intercept call site), so
    // there's nothing to carry into its "pending" page.
    return REDIRECT_ORGANIZATION_PREFIX + organizationId + "/login/email-link/pending";
  }

  @GetMapping("/pending-verification")
  public String pendingVerification(
      // Optional, never trusted for anything but display (see RedirectQueryParams's own Javadoc) —
      // a direct GET with no query string still renders the page, just without the personalized
      // "we sent it to X" line below.
      @RequestParam(required = false) final String email, final Model model) {
    model.addAttribute(EMAIL, email);
    return "identity/register-pending-verification";
  }

  private void addSignUpOptions(final UUID organizationId, final Model model) {
    final OrganizationId orgId = new OrganizationId(organizationId);
    final AccountAuthenticationPolicySnapshot policy = policyProvider.policyFor(orgId);
    model.addAttribute("usernameSignUpEnabled", policy.usernameSignUpEnabled());
    model.addAttribute("usernameRequired", policy.usernameRequired());
    model.addAttribute("passwordAtSignUpEnabled", policy.passwordAtSignUpEnabled());

    // Same ADR-0020 Decision 3/BR-ID-12 "computed fresh on every render" posture as
    // LoginController#addSignInOptions's own identical block — this page's own social-provider
    // buttons must reflect exactly the same allowed-providers set the sign-in page does, since
    // they resolve to the same underlying flow.
    final List<SocialProvider> enabledSocialProviders =
        new ArrayList<>(socialLoginPolicyProvider.allowedProviders(orgId));
    model.addAttribute("socialProviders", enabledSocialProviders);
  }
}
