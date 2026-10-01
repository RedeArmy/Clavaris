package com.clavaris.identity.application.usecase.registerwebauthncredential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.domain.model.AccountId;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StartWebAuthnRegistrationServiceTest {

  @Test
  void delegatesToTheRelyingPartyWithAUserIdentityBuiltFromTheCommand() {
    RelyingParty relyingParty = mock(RelyingParty.class);
    PublicKeyCredentialCreationOptions expected = mock(PublicKeyCredentialCreationOptions.class);
    when(relyingParty.startRegistration(any())).thenReturn(expected);
    StartWebAuthnRegistrationService service = new StartWebAuthnRegistrationService(relyingParty);
    AccountId accountId = AccountId.newId();

    PublicKeyCredentialCreationOptions result =
        service.handle(new StartWebAuthnRegistrationCommand(accountId, "user@example.com"));

    assertThat(result).isSameAs(expected);
    ArgumentCaptor<StartRegistrationOptions> captor =
        ArgumentCaptor.forClass(StartRegistrationOptions.class);
    verify(relyingParty).startRegistration(captor.capture());
    assertThat(captor.getValue().getUser().getName()).isEqualTo("user@example.com");
    assertThat(captor.getValue().getUser().getDisplayName()).isEqualTo("user@example.com");
    assertThat(new String(captor.getValue().getUser().getId().getBytes()))
        .isEqualTo(accountId.value().toString());
  }
}
