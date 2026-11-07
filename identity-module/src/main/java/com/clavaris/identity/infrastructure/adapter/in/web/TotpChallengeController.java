package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.codelockout.CodeLockout;
import com.clavaris.identity.application.usecase.codelockout.CodeTemporarilyLockedException;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.recordloginevent.RecordLoginEventUseCase;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver;
import com.clavaris.identity.application.usecase.verifytotpchallenge.InvalidTotpCodeException;
import com.clavaris.identity.application.usecase.verifytotpchallenge.VerifyTotpChallengeCommand;
import com.clavaris.identity.application.usecase.verifytotpchallenge.VerifyTotpChallengeUseCase;
import com.clavaris.identity.domain.model.AccountId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
import jakarta.validation.Valid;
import java.util.Optional;
import java.util.UUID;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * ADR-0040: the page that asks for the authenticator code after the primary factor, reached from
 * {@link TotpGate}. {@code organizationId} comes from the path and must match the one the paused
 * sign-in was started for, same BR-ORG-02 reasoning as {@link DeviceTrustChallengeController}.
 *
 * <p>The one field takes the six-digit code or a recovery code. A correct entry marks the session
 * with {@link SecondFactorSessionMarker}, so the establisher adds {@code mfa} to the claims, and
 * then resumes the sign-in through the same helper the other pauses use.
 */
@Controller
@RequestMapping("/o/{organizationId}/login/totp")
public class TotpChallengeController {

  private static final String LOGIN_REDIRECT_PREFIX = "redirect:/o/";

  private static final String FORM_VIEW = "identity/login-totp";

  private final VerifyTotpChallengeUseCase verifyUseCase;
  private final AuthenticatedSessionEstablisher sessions;
  private final RecordAccountLoginDeviceUseCase recordLoginDevice;
  private final RedirectUrlResolver redirectUrlResolver;
  private final RecordLoginEventUseCase recordLoginEvent;
  private final CodeLockout codeLockout;
  private final int maxEntriesPerChallenge;

  public TotpChallengeController(
      final VerifyTotpChallengeUseCase verifyUseCase,
      final AuthenticatedSessionEstablisher sessions,
      final RecordAccountLoginDeviceUseCase recordLoginDevice,
      final RedirectUrlResolver redirectUrlResolver,
      final RecordLoginEventUseCase recordLoginEvent,
      final CodeLockout codeLockout,
      @Value("${clavaris.totp.max-entries-per-challenge:5}") final int maxEntriesPerChallenge) {
    this.verifyUseCase = verifyUseCase;
    this.sessions = sessions;
    this.recordLoginDevice = recordLoginDevice;
    this.redirectUrlResolver = redirectUrlResolver;
    this.recordLoginEvent = recordLoginEvent;
    this.codeLockout = codeLockout;
    this.maxEntriesPerChallenge = maxEntriesPerChallenge;
  }

  @GetMapping
  public String showForm(
      @PathVariable final UUID organizationId,
      final HttpServletRequest request,
      final Model model) {
    final Optional<AccountId> pending = pendingAccountId(request, organizationId);
    if (pending.isEmpty()) {
      return LOGIN_REDIRECT_PREFIX + organizationId + "/login";
    }
    if (codeLockout.isLocked(CodeLockout.totpCodeKey(pending.get()))) {
      model.addAttribute("codeLocked", true);
      offerRecoveryCodeIfOpen(pending.get(), model);
    }
    model.addAttribute("form", new TotpChallengeForm());
    return FORM_VIEW;
  }

  // Three exits (validation error, wrong or paused, success), same rationale as
  // DeviceTrustChallengeController's own identical shape.
  @PostMapping
  public String confirm(
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute("form") final TotpChallengeForm form,
      final BindingResult bindingResult,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final Model model) {
    final Optional<AccountId> pending = pendingAccountId(request, organizationId);
    if (pending.isEmpty()) {
      return LOGIN_REDIRECT_PREFIX + organizationId + "/login";
    }
    if (bindingResult.hasErrors()) {
      return FORM_VIEW;
    }

    final AccountId accountId = pending.get();
    // TD-SEC-093: a pause for the code allows a few entries; when they are spent the sign-in starts
    // again from the password, so one password check cannot be used to pile wrong entries onto the
    // Account's own, longer pause.
    final UUID challengeId = challengeId(request.getSession(false));
    if (!codeLockout.tryChallengeEntry(challengeId, maxEntriesPerChallenge)) {
      abandonChallenge(request.getSession(false), challengeId);
      return LOGIN_REDIRECT_PREFIX + organizationId + "/login?totpAbandoned";
    }
    try {
      verifyUseCase.handle(new VerifyTotpChallengeCommand(accountId, form.getCode()));
    } catch (final InvalidTotpCodeException _) {
      model.addAttribute("codeError", true);
      return FORM_VIEW;
    } catch (final CodeTemporarilyLockedException _) {
      model.addAttribute("codeLocked", true);
      offerRecoveryCodeIfOpen(accountId, model);
      return FORM_VIEW;
    }

    final HttpSession session = request.getSession(true);
    codeLockout.endChallenge(challengeId);
    session.removeAttribute(TotpPendingState.CHALLENGE_ID_ATTRIBUTE);
    SecondFactorSessionMarker.mark(session, accountId.value());
    final boolean deviceTrustVerified =
        Boolean.parseBoolean(
            (String) session.getAttribute(TotpPendingState.DEVICE_TRUST_VERIFIED_ATTRIBUTE));
    session.removeAttribute(TotpPendingState.DEVICE_TRUST_VERIFIED_ATTRIBUTE);
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
            TotpPendingState.ACCOUNT_ID_ATTRIBUTE,
            TotpPendingState.FACTOR_ATTRIBUTE,
            TotpPendingState.ORGANIZATION_ID_ATTRIBUTE,
            TotpPendingState.CLIENT_ID_ATTRIBUTE,
            TotpPendingState.REDIRECT_URL_ATTRIBUTE,
            TotpPendingState.PROVIDER_ATTRIBUTE,
            null,
            deviceTrustVerified);
    return "redirect:" + redirectTarget;
  }

  // A pause begun before challenges were numbered has none yet: it gets one now, which is the
  // budget it would have had.
  private static UUID challengeId(final HttpSession session) {
    final Object raw = session.getAttribute(TotpPendingState.CHALLENGE_ID_ATTRIBUTE);
    if (raw instanceof String text) {
      return UUID.fromString(text);
    }
    final UUID fresh = UUID.randomUUID();
    session.setAttribute(TotpPendingState.CHALLENGE_ID_ATTRIBUTE, fresh.toString());
    return fresh;
  }

  private void abandonChallenge(final HttpSession session, final UUID challengeId) {
    codeLockout.endChallenge(challengeId);
    for (final String attribute :
        new String[] {
          TotpPendingState.ACCOUNT_ID_ATTRIBUTE,
          TotpPendingState.FACTOR_ATTRIBUTE,
          TotpPendingState.CHALLENGE_ID_ATTRIBUTE,
          TotpPendingState.ORGANIZATION_ID_ATTRIBUTE,
          TotpPendingState.CLIENT_ID_ATTRIBUTE,
          TotpPendingState.REDIRECT_URL_ATTRIBUTE,
          TotpPendingState.PROVIDER_ATTRIBUTE,
          TotpPendingState.DEVICE_TRUST_VERIFIED_ATTRIBUTE
        }) {
      session.removeAttribute(attribute);
    }
  }

  // TD-SEC-092: the app and the recovery codes are paused apart, so a paused app is not a dead end.
  private void offerRecoveryCodeIfOpen(final AccountId accountId, final Model model) {
    if (!codeLockout.isLocked(CodeLockout.totpRecoveryKey(accountId))) {
      model.addAttribute("recoveryCodeAvailable", true);
    }
  }

  private Optional<AccountId> pendingAccountId(
      final HttpServletRequest request, final UUID organizationId) {
    final HttpSession session = request.getSession(false);
    if (session == null) {
      return Optional.empty();
    }
    if (!organizationId
        .toString()
        .equals(session.getAttribute(TotpPendingState.ORGANIZATION_ID_ATTRIBUTE))) {
      return Optional.empty();
    }
    final Object rawAccountId = session.getAttribute(TotpPendingState.ACCOUNT_ID_ATTRIBUTE);
    return rawAccountId == null
        ? Optional.empty()
        : Optional.of(new AccountId(UUID.fromString((String) rawAccountId)));
  }
}
