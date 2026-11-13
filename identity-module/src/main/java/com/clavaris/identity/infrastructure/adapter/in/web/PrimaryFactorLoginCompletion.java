package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.OrganizationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.Optional;
import java.util.UUID;

/**
 * TD-ARCH-016: the {@code DeviceTrustGate}→{@code SessionTaskGate}→{@code
 * AuthenticatedSessionCompletion} sequence {@link LoginController}/{@code UsernameSignInController}
 * used to each carry as an identical, byte-for-byte-duplicated 46-line block — found live by
 * `pmd:cpd-check` (TD-PROC-009) the first time it ran, deliberately left duplicated (behind {@code
 * CPD-OFF}/{@code CPD-ON} markers) at the time since consolidating it needed a real design decision
 * (this class's own {@link PrimaryFactorLoginPorts} parameter object), not a same-day fix alongside
 * unrelated rows.
 *
 * <p>{@code factor} is a parameter, not hardcoded to {@code PASSWORD}, even though both of today's
 * two callers only ever pass that one value — the two blocks this replaces already computed it
 * independently rather than sharing a constant, and a parameter costs nothing extra at either call
 * site while leaving room for a future primary-factor controller (a third passwordless method
 * gaining device-trust/session-task support, say) to reuse this without also having to fork it.
 */
/* package */ final class PrimaryFactorLoginCompletion {

  private static final String REDIRECT_PREFIX = "redirect:";

  private PrimaryFactorLoginCompletion() {
    // Static utility — not instantiable.
  }

  /**
   * @return the Spring MVC redirect target the calling controller's own {@code @PostMapping} method
   *     should return directly — a device-trust challenge, a session-task pause, or the final
   *     post-login redirect, in that same priority order the original duplicated block established.
   */
  /* package */ static String completeAfterPrimaryFactor(
      final PrimaryFactorLoginPorts ports,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final UUID organizationId,
      final Account account,
      final PendingAuthenticationFactor factor,
      final String clientId,
      final String redirectUrl) {
    final Optional<String> challenge =
        DeviceTrustGate.intercept(
            ports.knownDevices(),
            ports.requestDeviceTrustChallenge(),
            ports.authenticationPolicyProvider().policyFor(new OrganizationId(organizationId)),
            request,
            organizationId,
            account.id(),
            factor,
            clientId,
            redirectUrl,
            account.bypassesDeviceTrust(),
            null);
    if (challenge.isPresent()) {
      return REDIRECT_PREFIX + challenge.get();
    }

    final Optional<String> sessionTask =
        SessionTaskGate.intercept(
            request, organizationId, account, factor, clientId, redirectUrl, null);
    if (sessionTask.isPresent()) {
      return REDIRECT_PREFIX + sessionTask.get();
    }

    // ADR-0040: the last pause, immediately before the session would exist.
    final Optional<String> secondFactor =
        TotpGate.intercept(
            ports.requiresSecondFactor(),
            request,
            organizationId,
            account.id(),
            factor,
            clientId,
            redirectUrl,
            null,
            false);
    if (secondFactor.isPresent()) {
      return REDIRECT_PREFIX + secondFactor.get();
    }

    final String redirectTarget =
        AuthenticatedSessionCompletion.complete(
            ports.sessions(),
            ports.recordLoginDevice(),
            ports.redirectUrlResolver(),
            ports.recordLoginEvent(),
            request,
            response,
            organizationId,
            account.id(),
            factor,
            clientId,
            redirectUrl,
            account);
    return REDIRECT_PREFIX + redirectTarget;
  }
}
