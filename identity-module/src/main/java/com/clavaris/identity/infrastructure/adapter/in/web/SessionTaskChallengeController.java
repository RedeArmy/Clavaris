package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.completeforcedpasswordreset.CompleteForcedPasswordResetCommand;
import com.clavaris.identity.application.usecase.completeforcedpasswordreset.CompleteForcedPasswordResetUseCase;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.recordloginevent.RecordLoginEventUseCase;
import com.clavaris.identity.application.usecase.registeraccount.BreachedPasswordException;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.service.PasswordPolicy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.Optional;
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
 * Clerk "session tasks" parity: the forced-password-reset challenge — reached only via a redirect
 * from {@link SessionTaskGate#intercept}, same "no direct entry point, landing here with no
 * matching pending state bounces back to ordinary login" posture as {@link
 * DeviceTrustChallengeController}, which this class otherwise mirrors closely.
 */
// PMD.LongVariable: redirectUrlResolver matches its own port type name, not arbitrarily long —
// same precedent DeviceTrustChallengeController's own identical suppression documents.
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/o/{organizationId}/login/session-task/password-reset")
public class SessionTaskChallengeController {

  private static final String FORM_VIEW = "identity/session-task-password-reset";

  private final CompleteForcedPasswordResetUseCase completeUseCase;
  private final AuthenticatedSessionEstablisher sessions;
  private final RecordAccountLoginDeviceUseCase recordLoginDevice;
  private final RedirectUrlResolver redirectUrlResolver;
  private final RecordLoginEventUseCase recordLoginEvent;

  public SessionTaskChallengeController(
      final CompleteForcedPasswordResetUseCase completeUseCase,
      final AuthenticatedSessionEstablisher sessions,
      final RecordAccountLoginDeviceUseCase recordLoginDevice,
      final RedirectUrlResolver redirectUrlResolver,
      final RecordLoginEventUseCase recordLoginEvent) {
    this.completeUseCase = completeUseCase;
    this.sessions = sessions;
    this.recordLoginDevice = recordLoginDevice;
    this.redirectUrlResolver = redirectUrlResolver;
    this.recordLoginEvent = recordLoginEvent;
  }

  // Two genuinely distinct exits (no pending task / render the form) — same rationale as
  // DeviceTrustChallengeController's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @GetMapping
  public String showForm(
      @PathVariable final UUID organizationId,
      final HttpServletRequest request,
      final Model model) {
    if (pendingAccountId(request, organizationId).isEmpty()) {
      return "redirect:/o/" + organizationId + "/login";
    }
    model.addAttribute("form", new SessionTaskPasswordResetForm());
    return FORM_VIEW;
  }

  // Four genuinely distinct exits (no pending task / validation error / mismatch / weak password)
  // — same "each outcome needs its own exit" rationale as ResetPasswordController's own identical
  // suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String confirm(
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute("form") final SessionTaskPasswordResetForm form,
      final BindingResult bindingResult,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Model model) {
    final Optional<AccountId> pending = pendingAccountId(request, organizationId);
    if (pending.isEmpty()) {
      return "redirect:/o/" + organizationId + "/login";
    }
    if (bindingResult.hasErrors()) {
      return FORM_VIEW;
    }
    if (!form.getNewPassword().equals(form.getConfirmPassword())) {
      bindingResult.rejectValue(
          "confirmPassword", "confirmPassword.mismatch", "Passwords do not match");
      return FORM_VIEW;
    }

    final AccountId accountId = pending.get();
    try {
      completeUseCase.handle(
          new CompleteForcedPasswordResetCommand(accountId, form.getNewPassword()));
    } catch (final WeakPasswordException _) {
      // Same rationale as ResetPasswordController's own identical fix.
      bindingResult.rejectValue(
          "newPassword",
          "newPassword.tooWeak",
          "Password must be between "
              + PasswordPolicy.MIN_LENGTH
              + " and "
              + PasswordPolicy.MAX_LENGTH
              + " characters");
      return FORM_VIEW;
    } catch (final BreachedPasswordException _) {
      // BR-ID-07: deliberately NOT the WeakPasswordException message slot above — generic wording
      // only, never mentioning a breach/source (same exception's own Javadoc).
      bindingResult.rejectValue(
          "newPassword",
          "newPassword.breached",
          "This password cannot be used - please choose a different one");
      return FORM_VIEW;
    }

    // This task's own completion is the actual moment the session finally gets established —
    // AuthenticatedSessionCompletion's own device-recording step reflects that.
    final HttpSession session = request.getSession(true);
    final String redirectTarget =
        AuthenticatedSessionCompletion.completeFromPendingChallenge(
            sessions,
            recordLoginDevice,
            redirectUrlResolver,
            recordLoginEvent,
            request,
            response,
            session,
            organizationId,
            accountId,
            SessionTaskPendingState.ACCOUNT_ID_ATTRIBUTE,
            SessionTaskPendingState.FACTOR_ATTRIBUTE,
            SessionTaskPendingState.ORGANIZATION_ID_ATTRIBUTE,
            SessionTaskPendingState.CLIENT_ID_ATTRIBUTE,
            SessionTaskPendingState.REDIRECT_URL_ATTRIBUTE,
            SessionTaskPendingState.PROVIDER_ATTRIBUTE,
            // TD-SEC-048: an operator-forced password reset is not a second authentication
            // factor — it proves the same credential type (a password) was reset, not that a
            // distinct factor was added, so this resume never composes an "mfa" amr value.
            false);
    return "redirect:" + redirectTarget;
  }

  // Two genuinely distinct outcomes (a pending task for this exact Organization / none at all) —
  // same rationale as DeviceTrustChallengeController's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private Optional<AccountId> pendingAccountId(
      final HttpServletRequest request, final UUID organizationId) {
    final HttpSession session = request.getSession(false);
    if (session == null) {
      return Optional.empty();
    }
    // BR-ORG-02: same cross-tenant defence-in-depth as DeviceTrustChallengeController's own
    // identical check.
    final Object pendingOrgId =
        session.getAttribute(SessionTaskPendingState.ORGANIZATION_ID_ATTRIBUTE);
    if (!organizationId.toString().equals(pendingOrgId)) {
      return Optional.empty();
    }
    final Object rawAccountId = session.getAttribute(SessionTaskPendingState.ACCOUNT_ID_ATTRIBUTE);
    if (rawAccountId == null) {
      return Optional.empty();
    }
    return Optional.of(new AccountId(UUID.fromString((String) rawAccountId)));
  }
}
