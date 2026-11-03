package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.recordaccountlogindevice.KnownDeviceRepository;
import com.clavaris.identity.application.usecase.requestdevicetrustchallenge.ChallengeReason;
import com.clavaris.identity.application.usecase.requestdevicetrustchallenge.RequestDeviceTrustChallengeCommand;
import com.clavaris.identity.application.usecase.requestdevicetrustchallenge.RequestDeviceTrustChallengeUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.application.usecase.requestemailverification.EmailVerificationMethod;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.SocialProvider;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

/**
 * Which factors the new-device challenge applies to (BR-ID-21). The decision that matters here is
 * ADR-0038's: it is a code mailed to the mailbox, so it is asked of a password, a social provider
 * and a passkey, and not of a sign-in that was itself an emailed code or link.
 */
class DeviceTrustGateTest {

  private final UUID organizationId = UUID.randomUUID();
  private final AccountId accountId = AccountId.newId();

  private KnownDeviceRepository knownDevices;
  private RequestDeviceTrustChallengeUseCase requestChallenge;
  private MockHttpServletRequest request;

  @BeforeEach
  void setUp() {
    knownDevices = mock(KnownDeviceRepository.class);
    requestChallenge = mock(RequestDeviceTrustChallengeUseCase.class);
    request = new MockHttpServletRequest();
    when(knownDevices.findByAccountIdAndDeviceTokenHash(any(), any())).thenReturn(Optional.empty());
  }

  private static AccountAuthenticationPolicySnapshot deviceTrustOn() {
    return new AccountAuthenticationPolicySnapshot(
        false, EmailVerificationMethod.LINK, true, true, false, false, true, true, false);
  }

  private Optional<String> intercept(
      final PendingAuthenticationFactor factor, final boolean bypass) {
    return DeviceTrustGate.intercept(
        knownDevices,
        requestChallenge,
        deviceTrustOn(),
        request,
        organizationId,
        accountId,
        factor,
        null,
        null,
        bypass,
        factor == PendingAuthenticationFactor.SOCIAL ? SocialProvider.GOOGLE : null);
  }

  @Test
  void anEmailedCodeOrLinkIsNeverFollowedByASecondCodeForANewDevice() {
    assertThat(intercept(PendingAuthenticationFactor.ONE_TIME_EMAIL_PROOF, false)).isEmpty();

    verify(requestChallenge, never()).handle(any());
  }

  @Test
  void aPasswordFromAnUnrecognisedDeviceIsStillChallenged() {
    Optional<String> challenge = intercept(PendingAuthenticationFactor.PASSWORD, false);

    assertThat(challenge).contains("/o/" + organizationId + "/login/device-trust");
    verify(requestChallenge)
        .handle(new RequestDeviceTrustChallengeCommand(accountId, ChallengeReason.NEW_DEVICE));
  }

  @Test
  void aSocialProviderAndAPasskeyAreStillChallengedFromAnUnrecognisedDevice() {
    assertThat(intercept(PendingAuthenticationFactor.SOCIAL, false)).isPresent();
    assertThat(intercept(PendingAuthenticationFactor.PASSKEY, false)).isPresent();
  }

  @Test
  void theOperatorsPerAccountBypassStillLiftsTheChallenge() {
    assertThat(intercept(PendingAuthenticationFactor.PASSWORD, true)).isEmpty();

    verify(requestChallenge, never()).handle(any());
  }
}
