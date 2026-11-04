package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.listwebauthncredentialsforaccount.ListWebAuthnCredentialsForAccountUseCase;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.WebAuthnCredential;
import jakarta.servlet.http.Cookie;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

class PasskeyOfferTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();
  private static final String NEXT = "/o/" + ORGANIZATION_ID + "/oauth2/authorize?client_id=abc";

  private ListWebAuthnCredentialsForAccountUseCase listCredentials;
  private PasskeyOffer offer;
  private final AccountId accountId = AccountId.newId();

  @BeforeEach
  void setUp() {
    listCredentials = mock(ListWebAuthnCredentialsForAccountUseCase.class);
    when(listCredentials.handle(any())).thenReturn(List.of());
    offer = new PasskeyOffer(listCredentials);
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
