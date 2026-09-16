package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountEmailAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.registerplatformaccount.RegisterPlatformAccountCommand;
import com.clavaris.identity.application.usecase.registerplatformaccount.RegisterPlatformAccountUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.MailDeliveryException;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.RequestPlatformAccountEmailVerificationCommand;
import com.clavaris.identity.application.usecase.requestplatformaccountemailverification.RequestPlatformAccountEmailVerificationUseCase;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.PlatformAccountId;
import jakarta.validation.groups.Default;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * ADR-0012: the self-service signup entry point for a {@code PlatformAccount} — no {@code
 * organizationId} in the path, unlike {@link RegisterAccountController}: a platform account belongs
 * to no Organization, it goes on to own zero or more.
 *
 * <p>SDE-III review, 2026-09-16 — real bug found live: {@code requestEmailVerification.handle(...)}
 * below used to run unguarded. The account (and its verification token) are already committed by
 * the time it runs — {@code RequestPlatformAccountEmailVerificationService}'s own token-then-mail
 * ordering means only the notification attempt can still fail — but a Resend
 * outage/misconfiguration (an unset {@code RESEND_API_KEY} fails loudly by design, {@code
 * application.yml}'s own comment) then threw {@link MailDeliveryException} straight through this
 * handler: an unhandled 500 on a request that had already succeeded, with no redirect and no
 * explanation, right after the one signal a real bug existed (the second submit's "already
 * registered") tells the caller nothing about the first. Caught here the same way {@code
 * RecordAccountLoginDeviceService}/{@code RecordPlatformAccountLoginDeviceService} already treat an
 * identically-shaped failure — logged, not swallowed silently, but never lets a side-channel
 * notification failure block an already-successful signup from reaching its own success page.
 */
@Controller
@RequestMapping("/platform/register")
public class RegisterPlatformAccountController {

  private static final Logger LOG =
      LoggerFactory.getLogger(RegisterPlatformAccountController.class);

  private static final String FORM_VIEW = "identity/platform/register";

  private final RegisterPlatformAccountUseCase useCase;

  @SuppressWarnings("PMD.LongVariable")
  private final RequestPlatformAccountEmailVerificationUseCase requestEmailVerification;

  public RegisterPlatformAccountController(
      final RegisterPlatformAccountUseCase useCase,
      @SuppressWarnings("PMD.LongVariable")
          final RequestPlatformAccountEmailVerificationUseCase requestEmailVerification) {
    this.useCase = useCase;
    this.requestEmailVerification = requestEmailVerification;
  }

  @GetMapping
  public String showForm(final Model model) {
    model.addAttribute("form", new RegisterPlatformAccountForm());
    return FORM_VIEW;
  }

  // PasswordRequired: this tier's own password is always mandatory, unlike RegisterAccountForm's
  // policy-driven one (ADR-0024 §5) — see that group's own Javadoc for why the two forms share one
  // field declaration instead of forking. Default.class must be listed explicitly alongside it:
  // @Validated with any explicit group list replaces the implicit Default-only behavior @Valid
  // gives, it doesn't add to it.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String register(
      @Validated({Default.class, PasswordRequired.class}) @ModelAttribute("form")
          final RegisterPlatformAccountForm form,
      final BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
      return FORM_VIEW;
    }

    if (!form.getPassword().equals(form.getConfirmPassword())) {
      bindingResult.rejectValue(
          "confirmPassword", "confirmPassword.mismatch", "Passwords do not match");
      return FORM_VIEW;
    }

    final PlatformAccountId accountId;
    try {
      accountId =
          useCase.handle(
              new RegisterPlatformAccountCommand(new Email(form.getEmail()), form.getPassword()));
    } catch (PlatformAccountEmailAlreadyRegisteredException _) {
      bindingResult.rejectValue(
          "email", "email.alreadyRegistered", "This email is already registered");
      return FORM_VIEW;
    } catch (WeakPasswordException _) {
      bindingResult.rejectValue(
          "password", "password.tooWeak", "Password does not meet the minimum requirements");
      return FORM_VIEW;
    }

    // See this class's own Javadoc addendum for the real, live-found bug this guards.
    try {
      requestEmailVerification.handle(
          new RequestPlatformAccountEmailVerificationCommand(accountId));
    } catch (final MailDeliveryException e) {
      LOG.warn("event=platform_account_registered_verification_email_send_failed", e);
    }

    // MAANG "check your email" parity — same RedirectQueryParams-mediated email hop as
    // RegisterAccountController's own identical redirect; see that method's own comment.
    String target = "redirect:/platform/register/pending-verification";
    target = RedirectQueryParams.appendIfPresent(target, "email", form.getEmail());
    return target;
  }

  @GetMapping("/pending-verification")
  public String pendingVerification(
      @RequestParam(required = false) final String email, final Model model) {
    model.addAttribute("email", email);
    return "identity/platform/register-pending-verification";
  }
}
