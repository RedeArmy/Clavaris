package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.recordaccountlogindevice.KnownDeviceRepository;
import com.clavaris.identity.application.usecase.recordaccountlogindevice.RecordAccountLoginDeviceUseCase;
import com.clavaris.identity.application.usecase.requestdevicetrustchallenge.RequestDeviceTrustChallengeUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.resolveredirecturl.RedirectUrlResolver;

/**
 * TD-ARCH-016: the 6 ports {@link PrimaryFactorLoginCompletion#completeAfterPrimaryFactor} needs —
 * bundled as one record parameter rather than a flat 6-parameter method signature, per this row's
 * own analysis in `technical-debt-register.md`. Each implementing controller ({@link
 * LoginController}, {@link UsernameSignInController}) builds exactly one of these once, in its own
 * constructor, from ports it already collaborates with individually — never a new dependency either
 * controller didn't already have.
 */
// PMD.LongVariable: requestDeviceTrustChallenge/authenticationPolicyProvider/redirectUrlResolver
// name exactly what they hold — same precedent LoginController/UsernameSignInController's own
// identical suppression already establishes for these same three names.
@SuppressWarnings("PMD.LongVariable")
/* package */ record PrimaryFactorLoginPorts(
    KnownDeviceRepository knownDevices,
    RequestDeviceTrustChallengeUseCase requestDeviceTrustChallenge,
    AccountAuthenticationPolicyProvider authenticationPolicyProvider,
    AuthenticatedSessionEstablisher sessions,
    RecordAccountLoginDeviceUseCase recordLoginDevice,
    RedirectUrlResolver redirectUrlResolver) {}
