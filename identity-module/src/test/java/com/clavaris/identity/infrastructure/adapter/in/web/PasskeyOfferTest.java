package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.listwebauthncredentialsforaccount.ListWebAuthnCredentialsForAccountUseCase;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.WebAuthnCredential;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

// BR-ID-29: a passkey is offered once per sign-in by emailed code or link, unless declined.
class PasskeyOfferTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();
  private static final String NEXT = "/o/" + ORGANIZATION_ID + "/oauth2/authorize?client_id=abc";

  private ListWebAuthnCredentialsForAccountUseCase listCredentials;
  private PasskeyOffer offer;
  private AccountAuthenticationPolicySnapshot policy =
      AccountAuthenticationPolicySnapshot.defaults();
  private final AccountId accountId = AccountId.newId();

  @BeforeEach
  void setUp() {
    listCredentials = mock(ListWebAuthnCredentialsForAccountUseCase.class);
    when(listCredentials.handle(any())).thenReturn(List.of());
    offer = new PasskeyOffer(listCredentials, organizationId -> policy);
  }

  private static AccountAuthenticationPolicySnapshot snapshot(
      final boolean passkeys, final boolean offer) {
    return new AccountAuthenticationPolicySnapshot(
        false,
        com.clavaris.identity.application.usecase.requestemailverification.EmailVerificationMethod
            .LINK,
        false,
        false,
        false,
        false,
        true,
        false,
        false,
        false,
        false,
        passkeys,
        offer);
  }

  @Test
  void anAccountWithoutAPasskeyIsOfferedOneAndWhereTheyWereGoingIsParked() {
    final MockHttpServletRequest request = new MockHttpServletRequest();

    assertThat(offer.interceptAfterEmailedProof(request, ORGANIZATION_ID, accountId, NEXT))
        .contains("/o/" + ORGANIZATION_ID + "/account/passkey-offer");
    assertThat(request.getSession().getAttribute(PasskeyOfferPendingState.NEXT_ATTRIBUTE))
        .isEqualTo(NEXT);
  }

  @Test
  void anOrganizationThatSwitchedTheOfferOffIsNeverOffered() {
    policy = snapshot(true, false);
    final MockHttpServletRequest request = new MockHttpServletRequest();

    assertThat(offer.interceptAfterEmailedProof(request, ORGANIZATION_ID, accountId, NEXT))
        .isEmpty();
    assertThat(request.getSession(false)).isNull();
  }

  @Test
  void anOrganizationWithPasskeysOffIsNeverOfferedEvenIfTheOfferIsOn() {
    policy = snapshot(false, true);

    assertThat(
            offer.interceptAfterEmailedProof(
                new MockHttpServletRequest(), ORGANIZATION_ID, accountId, NEXT))
        .isEmpty();
    assertThat(offer.isEnabledFor(ORGANIZATION_ID)).isFalse();
  }

  @Test
  void anAccountThatAlreadyHasAPasskeyIsNotOfferedAnother() {
    when(listCredentials.handle(any())).thenReturn(List.of(mock(WebAuthnCredential.class)));
    final MockHttpServletRequest request = new MockHttpServletRequest();

    assertThat(offer.interceptAfterEmailedProof(request, ORGANIZATION_ID, accountId, NEXT))
        .isEmpty();
    assertThat(request.getSession(false)).isNull();
  }

  @Test
  void aBrowserThatSaidDontAskAgainIsNotOffered() {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("clavaris_passkey_offer_" + ORGANIZATION_ID, "1"));

    assertThat(offer.interceptAfterEmailedProof(request, ORGANIZATION_ID, accountId, NEXT))
        .isEmpty();
  }

  @Test
  void theDismissalOfOneOrganizationDoesNotSilenceAnother() {
    final MockHttpServletRequest request = new MockHttpServletRequest();
    request.setCookies(new Cookie("clavaris_passkey_offer_" + UUID.randomUUID(), "1"));

    assertThat(offer.interceptAfterEmailedProof(request, ORGANIZATION_ID, accountId, NEXT))
        .isPresent();
  }
}
