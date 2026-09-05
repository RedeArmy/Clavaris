package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.completeforcedpasswordreset.CompleteForcedPasswordResetCommand;
import com.clavaris.identity.application.usecase.completeforcedpasswordreset.CompleteForcedPasswordResetUseCase;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver;
import com.clavaris.identity.domain.model.AccountId;
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

  public SessionTaskChallengeController(
      final CompleteForcedPasswordResetUseCase completeUseCase,
      final AuthenticatedSessionEstablisher sessions,
      final RecordAccountLoginDeviceUseCase recordLoginDevice,
      final RedirectUrlResolver redirectUrlResolver) {
    this.completeUseCase = completeUseCase;
    this.sessions = sessions;
    this.recordLoginDevice = recordLoginDevice;
    this.redirectUrlResolver = redirectUrlResolver;
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
      bindingResult.rejectValue(
          "newPassword", "newPassword.tooWeak", "Password does not meet the minimum requirements");
      return FORM_VIEW;
    }

    final HttpSession session = request.getSession(true);
    final PendingAuthenticationFactor factor =
        PendingAuthenticationFactor.valueOf(
            (String) session.getAttribute(SessionTaskPendingState.FACTOR_ATTRIBUTE));
    final String clientId =
        (String) session.getAttribute(SessionTaskPendingState.CLIENT_ID_ATTRIBUTE);
    final String redirectUrl =
        (String) session.getAttribute(SessionTaskPendingState.REDIRECT_URL_ATTRIBUTE);
    clearPendingState(session);

    // This task's own completion is the actual moment the session finally gets established —
    // AuthenticatedSessionCompletion's own device-recording step reflects that.
    final String redirectTarget =
        AuthenticatedSessionCompletion.complete(
            sessions,
            recordLoginDevice,
            redirectUrlResolver,
            request,
            response,
            organizationId,
            accountId,
            factor,
            clientId,
            redirectUrl);
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

  private void clearPendingState(final HttpSession session) {
    session.removeAttribute(SessionTaskPendingState.ACCOUNT_ID_ATTRIBUTE);
    session.removeAttribute(SessionTaskPendingState.FACTOR_ATTRIBUTE);
    session.removeAttribute(SessionTaskPendingState.ORGANIZATION_ID_ATTRIBUTE);
    session.removeAttribute(SessionTaskPendingState.CLIENT_ID_ATTRIBUTE);
    session.removeAttribute(SessionTaskPendingState.REDIRECT_URL_ATTRIBUTE);
  }
}
