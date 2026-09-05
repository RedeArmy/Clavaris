package com.clavaris.identity.infrastructure.adapter.in.web;

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
import com.clavaris.identity.application.usecase.requestemailverification.RequestEmailVerificationCommand;
import com.clavaris.identity.application.usecase.requestemailverification.RequestEmailVerificationUseCase;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import jakarta.validation.Valid;
import java.util.UUID;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

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
 */
// PMD.LongVariable: requestEmailVerification/requestEmailSignInCode/requestEmailSignInLink each
// name exactly which passwordless completion path they trigger — TD-SEC-004's own original
// rationale, extended to its two new siblings.
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/o/{organizationId}/register")
public class RegisterAccountController {

  private static final String FORM_VIEW = "identity/register";

  // Every redirect this controller issues targets this same Organization's own hosted UI — one
  // constant, not three repeated literals.
  private static final String REDIRECT_ORGANIZATION_PREFIX = "redirect:/o/";

  private final RegisterAccountUseCase useCase;
  private final RequestEmailVerificationUseCase requestEmailVerification;
  private final AccountAuthenticationPolicyProvider policyProvider;
  private final RequestEmailSignInCodeUseCase requestEmailSignInCode;
  private final RequestEmailSignInLinkUseCase requestEmailSignInLink;

  @SuppressWarnings({"java:S107", "PMD.LongVariable"})
  public RegisterAccountController(
      final RegisterAccountUseCase useCase,
      final RequestEmailVerificationUseCase requestEmailVerification,
      final AccountAuthenticationPolicyProvider policyProvider,
      final RequestEmailSignInCodeUseCase requestEmailSignInCode,
      final RequestEmailSignInLinkUseCase requestEmailSignInLink) {
    this.useCase = useCase;
    this.requestEmailVerification = requestEmailVerification;
    this.policyProvider = policyProvider;
    this.requestEmailSignInCode = requestEmailSignInCode;
    this.requestEmailSignInLink = requestEmailSignInLink;
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
      final Model model) {
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
          "email", "email.alreadyRegistered", "This email is already registered");
      addSignUpOptions(organizationId, model);
      return FORM_VIEW;
    } catch (WeakPasswordException _) {
      bindingResult.rejectValue(
          "password", "password.tooWeak", "Password does not meet the minimum requirements");
      addSignUpOptions(organizationId, model);
      return FORM_VIEW;
    } catch (UsernameRequiredException _) {
      bindingResult.rejectValue("username", "username.required", "Username is required");
      addSignUpOptions(organizationId, model);
      return FORM_VIEW;
    } catch (UsernameAlreadyRegisteredException _) {
      bindingResult.rejectValue(
          "username", "username.alreadyRegistered", "This username is already taken");
      addSignUpOptions(organizationId, model);
      return FORM_VIEW;
    }

    if (!passwordSubmitted) {
      // ADR-0024 §5: no password credential the account holder actually knows — completing sign-up
      // means completing whichever passwordless method the policy enabled, reusing §3's own
      // use cases entirely rather than a third, duplicated flow.
      return completePasswordlessSignUp(organizationId, orgId, form, policy);
    }

    // TD-SEC-004: this is the fix — a real send, triggered directly from the request that just
    // created the account, not left to an outbox row nothing drains yet (AccountRegisteredEvent's
    // own Javadoc documents that this is a deliberate divergence from its "async via outbox"
    // language, for exactly that reason).
    requestEmailVerification.handle(new RequestEmailVerificationCommand(accountId));

    return REDIRECT_ORGANIZATION_PREFIX + organizationId + "/register/pending-verification";
  }

  // Two genuinely distinct exits (email-code vs. email-link completion) — same "one exit per
  // distinct outcome" rationale this codebase's own guard-clause-heavy resolution logic already
  // documents elsewhere.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private String completePasswordlessSignUp(
      final UUID organizationId,
      final OrganizationId orgId,
      final RegisterAccountForm form,
      final AccountAuthenticationPolicySnapshot policy) {
    final Email email = new Email(form.getEmail());
    if (policy.emailCodeSignInEnabled()) {
      requestEmailSignInCode.handle(new RequestEmailSignInCodeCommand(orgId, email));
      return REDIRECT_ORGANIZATION_PREFIX
          + organizationId
          + "/login/email-code/confirm?email="
          + form.getEmail();
    }
    // SetAccountAuthenticationPolicyForOrganizationService's own validation already guarantees at
    // least one of the two is enabled whenever passwordAtSignUpEnabled is off — this is the only
    // remaining possibility, not a silently-assumed one.
    requestEmailSignInLink.handle(new RequestEmailSignInLinkCommand(orgId, email));
    return REDIRECT_ORGANIZATION_PREFIX + organizationId + "/login/email-link/pending";
  }

  @GetMapping("/pending-verification")
  public String pendingVerification() {
    return "identity/register-pending-verification";
  }

  private void addSignUpOptions(final UUID organizationId, final Model model) {
    final AccountAuthenticationPolicySnapshot policy =
        policyProvider.policyFor(new OrganizationId(organizationId));
    model.addAttribute("usernameSignUpEnabled", policy.usernameSignUpEnabled());
    model.addAttribute("usernameRequired", policy.usernameRequired());
    model.addAttribute("passwordAtSignUpEnabled", policy.passwordAtSignUpEnabled());
  }
}
