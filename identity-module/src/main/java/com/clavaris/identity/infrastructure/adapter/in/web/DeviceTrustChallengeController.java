package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.confirmdevicetrustchallenge.ConfirmDeviceTrustChallengeCommand;
import com.clavaris.identity.application.usecase.confirmdevicetrustchallenge.ConfirmDeviceTrustChallengeUseCase;
import com.clavaris.identity.application.usecase.confirmdevicetrustchallenge.InvalidDeviceTrustChallengeException;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceCommand;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectAction;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
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
 * ADR-0024 §6: the device-trust step-up challenge — reached only via a redirect from {@link
 * DeviceTrustGate#intercept}, never linked from {@code login.html} directly (unlike every other
 * controller in this package, there is no "sign in this way" entry point a user picks; landing here
 * with no matching pending session state means the flow was entered out of order, so both handlers
 * bounce back to the ordinary login page rather than erroring). Completes whichever primary-factor
 * {@link AuthenticatedSessionEstablisher} call the interrupted login had deferred — see {@link
 * PendingAuthenticationFactor}'s own Javadoc for why only those two factors are represented.
 */
// PMD.LongVariable: redirectUrlResolver matches its own port type name, not arbitrarily long —
// same precedent RedirectUrlResolver's own suppression documents.
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/o/{organizationId}/login/device-trust")
public class DeviceTrustChallengeController {

  private static final String FORM_VIEW = "identity/device-trust-challenge";

  private final ConfirmDeviceTrustChallengeUseCase confirmUseCase;
  private final AuthenticatedSessionEstablisher sessions;
  private final RecordAccountLoginDeviceUseCase recordLoginDevice;
  private final RedirectUrlResolver redirectUrlResolver;

  public DeviceTrustChallengeController(
      final ConfirmDeviceTrustChallengeUseCase confirmUseCase,
      final AuthenticatedSessionEstablisher sessions,
      final RecordAccountLoginDeviceUseCase recordLoginDevice,
      final RedirectUrlResolver redirectUrlResolver) {
    this.confirmUseCase = confirmUseCase;
    this.sessions = sessions;
    this.recordLoginDevice = recordLoginDevice;
    this.redirectUrlResolver = redirectUrlResolver;
  }

  // Two genuinely distinct exits (no pending challenge / render the form) — same "each outcome
  // needs its own exit" rationale as DeviceCookie's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @GetMapping
  public String showForm(
      @PathVariable final UUID organizationId,
      final HttpServletRequest request,
      final Model model) {
    if (pendingAccountId(request, organizationId).isEmpty()) {
      return "redirect:/o/" + organizationId + "/login";
    }
    model.addAttribute("form", new DeviceTrustChallengeForm());
    return FORM_VIEW;
  }

  // Three genuinely distinct exits (no pending challenge / validation error / invalid code) — same
  // "each outcome needs its own exit" rationale as ResetPasswordController's own identical
  // suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String confirm(
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute("form") final DeviceTrustChallengeForm form,
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

    final AccountId accountId = pending.get();
    try {
      confirmUseCase.handle(new ConfirmDeviceTrustChallengeCommand(accountId, form.getCode()));
    } catch (final InvalidDeviceTrustChallengeException _) {
      model.addAttribute("codeError", true);
      return FORM_VIEW;
    }

    final HttpSession session = request.getSession(true);
    final PendingAuthenticationFactor factor =
        PendingAuthenticationFactor.valueOf(
            (String) session.getAttribute(DeviceTrustPendingState.FACTOR_ATTRIBUTE));
    final String clientId =
        (String) session.getAttribute(DeviceTrustPendingState.CLIENT_ID_ATTRIBUTE);
    final String redirectUrl =
        (String) session.getAttribute(DeviceTrustPendingState.REDIRECT_URL_ATTRIBUTE);
    clearPendingState(session);

    // Device trust only ever gates a sign-in (never sign-up completion, ADR-0024 §6) — always
    // RedirectAction.SIGN_IN, same as every one of this challenge's four possible callers.
    final String fallbackUrl =
        redirectUrlResolver
            .resolve(
                new OrganizationId(organizationId), clientId, redirectUrl, RedirectAction.SIGN_IN)
            .orElse("/o/" + organizationId + "/login?authenticated");
    final String redirectTarget =
        factor == PendingAuthenticationFactor.ONE_TIME_EMAIL_PROOF
            ? sessions.establishViaOneTimeEmailProof(
                request, response, accountId.value(), fallbackUrl)
            : sessions.establish(request, response, accountId.value(), fallbackUrl);

    // Same device-recording step every other sign-in controller's own POST handler performs — see
    // RecordAccountLoginDeviceService's own Javadoc for why this never throws. The device is
    // recorded as known only now, after the challenge actually succeeded — never at the moment the
    // challenge was merely issued.
    recordLoginDevice
        .handle(
            new RecordAccountLoginDeviceCommand(
                accountId,
                request.getHeader("User-Agent"),
                request.getRemoteAddr(),
                DeviceCookie.read(request, organizationId).orElse(null)))
        .ifPresent(
            rawDeviceToken ->
                DeviceCookie.write(request, response, organizationId, rawDeviceToken));

    return "redirect:" + redirectTarget;
  }

  // Two genuinely distinct outcomes (a pending challenge for this exact Organization / none at
  // all) — same "each outcome needs its own exit" rationale as DeviceCookie's own identical
  // suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private Optional<AccountId> pendingAccountId(
      final HttpServletRequest request, final UUID organizationId) {
    final HttpSession session = request.getSession(false);
    if (session == null) {
      return Optional.empty();
    }
    // BR-ORG-02: a challenge started under a different Organization's own login is never
    // resumable from this one's URL, even within the very same browser session.
    final Object pendingOrgId =
        session.getAttribute(DeviceTrustPendingState.ORGANIZATION_ID_ATTRIBUTE);
    if (!organizationId.toString().equals(pendingOrgId)) {
      return Optional.empty();
    }
    final Object rawAccountId = session.getAttribute(DeviceTrustPendingState.ACCOUNT_ID_ATTRIBUTE);
    if (rawAccountId == null) {
      return Optional.empty();
    }
    return Optional.of(new AccountId(UUID.fromString((String) rawAccountId)));
  }

  private void clearPendingState(final HttpSession session) {
    session.removeAttribute(DeviceTrustPendingState.ACCOUNT_ID_ATTRIBUTE);
    session.removeAttribute(DeviceTrustPendingState.FACTOR_ATTRIBUTE);
    session.removeAttribute(DeviceTrustPendingState.ORGANIZATION_ID_ATTRIBUTE);
    session.removeAttribute(DeviceTrustPendingState.CLIENT_ID_ATTRIBUTE);
    session.removeAttribute(DeviceTrustPendingState.REDIRECT_URL_ATTRIBUTE);
  }
}
