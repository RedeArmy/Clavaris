package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceCommand;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.recordloginevent.RecordLoginEventCommand;
import com.clavaris.identity.application.usecase.recordloginevent.RecordLoginEventUseCase;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectAction;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialProvider;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.servlet.http.HttpSession;
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
 *
 * <p>TD-PERF-015 (closed): the 4-argument-tail {@link #complete} below is unchanged, still used by
 * both challenge controllers (a resumed login has only an {@link AccountId} recovered from the
 * session, never a full {@link Account} in hand) — see the new overload's own Javadoc for the one
 * real caller that does.
 *
 * <p>TD-FUT-034 (Clerk activity heatmap parity): {@code recordLoginEvent} joined every overload the
 * same way {@code recordLoginDevice} already had — this class's own four-controller chokepoint
 * (plus {@code EmailLinkSignInController}/{@code SocialLoginAuthenticationSuccessHandler}'s own two
 * direct calls, which never went through this class in the first place and call it independently)
 * is where every tenant sign-in this heatmap needs to count actually converges.
 */
// PMD.AvoidDuplicateLiterals: the repeated string is "PMD.LongVariable" itself, applied on four
// separate @SuppressWarnings within this file — same precedent
// SpringDataOrganizationJpaRepository's
// own identical suppression already documents for this exact shape.
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
final class AuthenticatedSessionCompletion {

  private AuthenticatedSessionCompletion() {
    // Static utility — not instantiable, same shape as DeviceTrustGate/SessionTaskGate.
  }

  /**
   * {@link DeviceTrustChallengeController#confirm} and {@link
   * SessionTaskChallengeController#confirm} independently resolved this exact same "read the paused
   * login's session attributes, clear them, then complete" tail — real, PMD-CPD-flagged duplication
   * (caught by {@code mvn verify} on this branch), not a style nitpick. Extracted here once, same
   * "found and fixed in the same pass" precedent as this class's own SonarCloud- duplication
   * history above. Takes the caller's own six {@code *PendingState} attribute-key constants
   * directly rather than forcing a shared interface on {@code DeviceTrustPendingState}/ {@code
   * SessionTaskPendingState} neither class needs for any other reason.
   *
   * @param deviceTrustVerified TD-SEC-048: true only for {@link DeviceTrustChallengeController}'s
   *     own resume — a genuine second authentication factor was just proven. {@link
   *     SessionTaskChallengeController}'s own resume (an operator-forced password reset, not a
   *     second factor) always passes {@code false}.
   */
  // java:S107/PMD.ExcessiveParameterList: one parameter per collaborating port/request value plus
  // the six session-attribute keys the caller's own pending-state class defines — same rationale
  // as this file's own complete() overloads below. PMD.LongVariable: the *Attribute parameter
  // names spell out exactly which session attribute each one reads.
  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList", "PMD.LongVariable"})
  /* package */ static String completeFromPendingChallenge(
      final AuthenticatedSessionEstablisher sessions,
      final RecordAccountLoginDeviceUseCase recordLoginDevice,
      final RedirectUrlResolver redirectUrlResolver,
      final RecordLoginEventUseCase recordLoginEvent,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final HttpSession session,
      final UUID organizationId,
      final AccountId accountId,
      final String accountIdAttribute,
      final String factorAttribute,
      final String organizationIdAttribute,
      final String clientIdAttribute,
      final String redirectUrlAttribute,
      final String providerAttribute,
      final boolean deviceTrustVerified) {
    final PendingAuthenticationFactor factor =
        PendingAuthenticationFactor.valueOf((String) session.getAttribute(factorAttribute));
    final String clientId = (String) session.getAttribute(clientIdAttribute);
    final String redirectUrl = (String) session.getAttribute(redirectUrlAttribute);
    final String providerValue = (String) session.getAttribute(providerAttribute);
    final SocialProvider provider =
        providerValue == null ? null : SocialProvider.valueOf(providerValue);

    session.removeAttribute(accountIdAttribute);
    session.removeAttribute(factorAttribute);
    session.removeAttribute(organizationIdAttribute);
    session.removeAttribute(clientIdAttribute);
    session.removeAttribute(redirectUrlAttribute);
    session.removeAttribute(providerAttribute);

    return complete(
        sessions,
        recordLoginDevice,
        redirectUrlResolver,
        recordLoginEvent,
        request,
        response,
        organizationId,
        accountId,
        factor,
        clientId,
        redirectUrl,
        null,
        provider,
        deviceTrustVerified);
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
      final RecordLoginEventUseCase recordLoginEvent,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final UUID organizationId,
      final AccountId accountId,
      final PendingAuthenticationFactor factor,
      final String clientId,
      final String redirectUrl) {
    return complete(
        sessions,
        recordLoginDevice,
        redirectUrlResolver,
        recordLoginEvent,
        request,
        response,
        organizationId,
        accountId,
        factor,
        clientId,
        redirectUrl,
        null,
        null,
        false);
  }

  /**
   * TD-PERF-015: same as the 11-argument {@link #complete} above, plus {@code preloadedAccount} —
   * the {@link Account} row matching {@code accountId}, when the caller already has it (every
   * primary-factor controller, moments after its own {@code Authenticate*UseCase} already loaded
   * it) — threaded into {@link RecordAccountLoginDeviceCommand} so it never has to re-fetch what
   * this request already has in memory. {@code null} is fully supported (see the other overload).
   */
  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList", "PMD.LongVariable"})
  /* package */ static String complete(
      final AuthenticatedSessionEstablisher sessions,
      final RecordAccountLoginDeviceUseCase recordLoginDevice,
      final RedirectUrlResolver redirectUrlResolver,
      final RecordLoginEventUseCase recordLoginEvent,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final UUID organizationId,
      final AccountId accountId,
      final PendingAuthenticationFactor factor,
      final String clientId,
      final String redirectUrl,
      final Account preloadedAccount) {
    return complete(
        sessions,
        recordLoginDevice,
        redirectUrlResolver,
        recordLoginEvent,
        request,
        response,
        organizationId,
        accountId,
        factor,
        clientId,
        redirectUrl,
        preloadedAccount,
        null,
        false);
  }

  /**
   * TD-SEC-055: same as the 12-argument {@link #complete} above, plus {@code socialProvider} — only
   * ever non-null when {@code factor == SOCIAL} (a device-trust/session-task pause resumed for a
   * social login), read back from {@code DeviceTrustPendingState}/{@code SessionTaskPendingState}'s
   * own {@code PROVIDER_ATTRIBUTE} by the two challenge controllers. {@code null} for every other
   * factor, same as {@code preloadedAccount} above.
   *
   * @param deviceTrustVerified TD-SEC-048: see {@link #completeFromPendingChallenge}'s own Javadoc.
   */
  @SuppressWarnings({"java:S107", "PMD.ExcessiveParameterList", "PMD.LongVariable"})
  /* package */ static String complete(
      final AuthenticatedSessionEstablisher sessions,
      final RecordAccountLoginDeviceUseCase recordLoginDevice,
      final RedirectUrlResolver redirectUrlResolver,
      final RecordLoginEventUseCase recordLoginEvent,
      final HttpServletRequest request,
      final HttpServletResponse response,
      final UUID organizationId,
      final AccountId accountId,
      final PendingAuthenticationFactor factor,
      final String clientId,
      final String redirectUrl,
      final Account preloadedAccount,
      final SocialProvider socialProvider,
      final boolean deviceTrustVerified) {
    final String fallbackUrl =
        redirectUrlResolver
            .resolve(
                new OrganizationId(organizationId), clientId, redirectUrl, RedirectAction.SIGN_IN)
            .orElse("/o/" + organizationId + "/login?authenticated");
    final String redirectTarget =
        switch (factor) {
          case ONE_TIME_EMAIL_PROOF ->
              sessions.establishViaOneTimeEmailProof(
                  request, response, accountId.value(), deviceTrustVerified, fallbackUrl);
          case SOCIAL ->
              sessions.establishViaSocialLogin(
                  request,
                  response,
                  accountId.value(),
                  socialProvider,
                  deviceTrustVerified,
                  fallbackUrl);
          case PASSWORD ->
              sessions.establish(
                  request, response, accountId.value(), deviceTrustVerified, fallbackUrl);
        };

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
                DeviceCookie.read(request, organizationId).orElse(null),
                preloadedAccount))
        .ifPresent(
            rawDeviceToken ->
                DeviceCookie.write(request, response, organizationId, rawDeviceToken));

    // TD-FUT-034: one row per completed tenant sign-in, for the "View Profile" activity heatmap —
    // see RecordLoginEventService's own Javadoc for why this never throws either.
    recordLoginEvent.handle(
        new RecordLoginEventCommand(accountId, new OrganizationId(organizationId)));

    return redirectTarget;
  }
}
