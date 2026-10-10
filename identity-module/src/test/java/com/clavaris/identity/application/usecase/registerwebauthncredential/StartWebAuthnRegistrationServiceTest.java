package com.clavaris.identity.application.usecase.registerwebauthncredential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.resolveorganizationname.OrganizationNameProvider;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class StartWebAuthnRegistrationServiceTest {

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final RelyingParty relyingParty = mock(RelyingParty.class);
  private final RelyingPartyFactory relyingParties = mock(RelyingPartyFactory.class);
  private final OrganizationNameProvider organizationNames = mock(OrganizationNameProvider.class);
  private final StartWebAuthnRegistrationService service =
      new StartWebAuthnRegistrationService(relyingParties, organizationNames);

  @Test
  void delegatesToTheRelyingPartyWithAUserIdentityBuiltFromTheCommand() {
    PublicKeyCredentialCreationOptions expected = mock(PublicKeyCredentialCreationOptions.class);
    when(organizationNames.nameFor(organizationId)).thenReturn(Optional.of("Acme Analytics"));
    when(relyingParties.named("Acme Analytics")).thenReturn(relyingParty);
    when(relyingParty.startRegistration(any())).thenReturn(expected);
    AccountId accountId = AccountId.newId();

    PublicKeyCredentialCreationOptions result =
        service.handle(
            new StartWebAuthnRegistrationCommand(accountId, organizationId, "user@example.com"));

    assertThat(result).isSameAs(expected);
    ArgumentCaptor<StartRegistrationOptions> captor =
        ArgumentCaptor.forClass(StartRegistrationOptions.class);
    verify(relyingParty).startRegistration(captor.capture());
    assertThat(captor.getValue().getUser().getName()).isEqualTo("user@example.com");
    assertThat(captor.getValue().getUser().getDisplayName()).isEqualTo("user@example.com");
    assertThat(new String(captor.getValue().getUser().getId().getBytes()))
        .isEqualTo(accountId.value().toString());
  }

  // The browser's "save a passkey for ..." prompt names the Organization the Account belongs to,
  // never Clavaris.
  @Test
  void theRelyingPartyIsNamedAfterTheAccountsOrganization() {
    when(organizationNames.nameFor(organizationId)).thenReturn(Optional.of("Acme Analytics"));
    when(relyingParties.named("Acme Analytics")).thenReturn(relyingParty);

    service.handle(
        new StartWebAuthnRegistrationCommand(
            AccountId.newId(), organizationId, "user@example.com"));

    verify(relyingParties).named("Acme Analytics");
  }

  // An Organization whose name cannot be found: the factory is asked for no name at all, and shows
  // the host instead of falling back to Clavaris.
  @Test
  void withNoOrganizationNameTheFactoryIsAskedForNone() {
    when(organizationNames.nameFor(organizationId)).thenReturn(Optional.empty());
    when(relyingParties.named(null)).thenReturn(relyingParty);

    service.handle(
        new StartWebAuthnRegistrationCommand(
            AccountId.newId(), organizationId, "user@example.com"));

    verify(relyingParties).named(null);
  }
}
