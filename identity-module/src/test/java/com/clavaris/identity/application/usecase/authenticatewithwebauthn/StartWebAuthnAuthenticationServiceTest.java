package com.clavaris.identity.application.usecase.authenticatewithwebauthn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.yubico.webauthn.AssertionRequest;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartAssertionOptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StartWebAuthnAuthenticationServiceTest {

  @Test
  void delegatesToTheRelyingPartyWithNoUsernameOrUserHandle() {
    RelyingParty relyingParty = mock(RelyingParty.class);
    AssertionRequest expected = mock(AssertionRequest.class);
    when(relyingParty.startAssertion(any())).thenReturn(expected);
    StartWebAuthnAuthenticationService service =
        new StartWebAuthnAuthenticationService(relyingParty);

    AssertionRequest result = service.handle();

    assertThat(result).isSameAs(expected);
    ArgumentCaptor<StartAssertionOptions> captor =
        ArgumentCaptor.forClass(StartAssertionOptions.class);
    verify(relyingParty).startAssertion(captor.capture());
    assertThat(captor.getValue().getUsername()).isEmpty();
    assertThat(captor.getValue().getUserHandle()).isEmpty();
  }
}
