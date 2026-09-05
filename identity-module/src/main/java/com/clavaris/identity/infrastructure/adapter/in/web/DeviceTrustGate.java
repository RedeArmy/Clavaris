package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.recordaccountlogindevice.KnownDeviceRepository;
import com.clavaris.identity.application.usecase.requestdevicetrustchallenge.RequestDeviceTrustChallengeCommand;
import com.clavaris.identity.application.usecase.requestdevicetrustchallenge.RequestDeviceTrustChallengeUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import java.util.Optional;
import java.util.UUID;

/**
 * ADR-0024 §6: the shared "should this just-authenticated login be paused for a device-trust
 * step-up?" check every primary-factor controller ({@link LoginController}, {@link
 * UsernameSignInController}, {@link EmailCodeSignInController}, {@link EmailLinkSignInController})
 * runs right after its own {@code Authenticate*UseCase} succeeds and before calling {@link
 * AuthenticatedSessionEstablisher} — a stateless static utility, same shape as {@link DeviceCookie}
 * (no collaborating port holds state worth a Spring bean of its own; each caller already holds the
 * two ports this needs).
 *
 * <p>Recognition reuses the exact same primitive {@code RecordAccountLoginDeviceService} already
 * uses ({@link KnownDeviceRepository#findByAccountIdAndDeviceTokenHash}) — deliberately called
 * <em>before</em> that service's own side-effecting {@code handle}, so an unrecognized device is
 * caught before the session is ever established, not only reported afterward as it is today for
 * Organizations with {@code deviceTrustEnabled=false}.
 *
 * <p><b>Named limitation (technical-debt-register.md):</b> a device recognized before {@code
 * deviceTrustEnabled} was turned on for this Organization stays recognized — this check only ever
 * challenges a genuinely new device going forward, never retroactively challenges an
 * already-trusted one the moment the policy flips on.
 */
final class DeviceTrustGate {

  private DeviceTrustGate() {
    // Static utility — not instantiable.
  }

  /**
   * @return the device-trust challenge redirect URL when this login must be paused; empty when it
   *     may proceed straight to establishing the session (policy disabled, or the presented {@link
   *     DeviceCookie} matches a {@code KnownDevice} already on file for this exact account).
   */
  // Two genuinely distinct outcomes (must pause for a challenge / may proceed) — same "each
  // outcome needs its own exit" rationale as DeviceCookie's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  /* package */ static Optional<String> intercept(
      final KnownDeviceRepository knownDevices,
      final RequestDeviceTrustChallengeUseCase requestChallenge,
      final AccountAuthenticationPolicySnapshot policy,
      final HttpServletRequest request,
      final UUID organizationId,
      final AccountId accountId,
      final PendingAuthenticationFactor factor) {
    if (!policy.deviceTrustEnabled()
        || isRecognized(knownDevices, request, organizationId, accountId)) {
      return Optional.empty();
    }

    final HttpSession session = request.getSession(true);
    session.setAttribute(
        DeviceTrustPendingState.ACCOUNT_ID_ATTRIBUTE, accountId.value().toString());
    session.setAttribute(DeviceTrustPendingState.FACTOR_ATTRIBUTE, factor.name());
    session.setAttribute(
        DeviceTrustPendingState.ORGANIZATION_ID_ATTRIBUTE, organizationId.toString());

    requestChallenge.handle(new RequestDeviceTrustChallengeCommand(accountId));
    return Optional.of("/o/" + organizationId + "/login/device-trust");
  }

  private static boolean isRecognized(
      final KnownDeviceRepository knownDevices,
      final HttpServletRequest request,
      final UUID organizationId,
      final AccountId accountId) {
    return DeviceCookie.read(request, organizationId)
        .filter(
            rawToken ->
                knownDevices
                    .findByAccountIdAndDeviceTokenHash(accountId, RefreshTokenSecret.hash(rawToken))
                    .isPresent())
        .isPresent();
  }
}
