package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceCommand;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectAction;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;

/**
 * SonarCloud duplication finding (5.2% on new code, closed): the exact same
 * "resolve-fallback-then-establish-then-record-device" tail was independently copy-pasted into
 * every controller that ever completes a sign-in — {@link LoginController}, {@link
 * UsernameSignInController}, {@link EmailCodeSignInController}, {@link
 * DeviceTrustChallengeController}, {@link SessionTaskChallengeController} — the moment {@code
 * RedirectUrlResolver} (Clerk "customize redirect URLs" parity) added a few lines to what used to
 * be a two-line block. Extracted here once, same static-utility shape as {@link DeviceTrustGate}/
 * {@link SessionTaskGate} (no collaborating port holds state worth a Spring bean of its own; each
 * caller already holds every port this needs).
 *
 * <p>Always resolves {@link RedirectAction#SIGN_IN} — every current caller is a sign-in completion
 * (including both challenge controllers' own resumed logins); a sign-up completion path would need
 * its own call, not a hidden branch here.
 */
final class AuthenticatedSessionCompletion {

  private AuthenticatedSessionCompletion() {
    // Static utility — not instantiable, same shape as DeviceTrustGate/SessionTaskGate.
  }

  // One parameter per collaborating port/request value — same rationale as this package's own
  // establishWithAuthorities (app module) and every DeviceTrustGate.intercept-style method here.
  // PMD.LongVariable: redirectUrlResolver matches its own port type name, not arbitrarily long —
  // same precedent DeviceTrustChallengeController's own identical suppression documents.
  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList", "PMD.LongVariable"})
  /* package */ static String complete(
      final AuthenticatedSessionEstablisher sessions,
      final RecordAccountLoginDeviceUseCase recordLoginDevice,
      final RedirectUrlResolver redirectUrlResolver,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final UUID organizationId,
      final AccountId accountId,
      final PendingAuthenticationFactor factor,
      final String clientId,
      final String redirectUrl) {
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

    // New-device login email notification — after establish(), same accountId/request already in
    // scope; see RecordAccountLoginDeviceService's own Javadoc for why this never throws. A
    // present return value means an unrecognized/absent DeviceCookie just got a fresh one minted
    // for it — write it back onto the response so the browser actually keeps it.
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

    return redirectTarget;
  }
}
