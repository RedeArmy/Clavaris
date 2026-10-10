package com.clavaris.identity.application.usecase.registerwebauthncredential;

import com.clavaris.identity.application.usecase.resolveorganizationname.OrganizationNameProvider;
import com.yubico.webauthn.StartRegistrationOptions;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.UserIdentity;
import java.nio.charset.StandardCharsets;

public class StartWebAuthnRegistrationService implements StartWebAuthnRegistrationUseCase {

  private final RelyingPartyFactory relyingParties;
  private final OrganizationNameProvider organizationNames;

  public StartWebAuthnRegistrationService(
      final RelyingPartyFactory relyingParties, final OrganizationNameProvider organizationNames) {
    this.relyingParties = relyingParties;
    this.organizationNames = organizationNames;
  }

  @Override
  public PublicKeyCredentialCreationOptions handle(final StartWebAuthnRegistrationCommand command) {
    // The WebAuthn "user handle" is simply the Account's own UUID, UTF-8 encoded — well within the
    // spec's 64-byte limit, globally unique, and needs no separate identifier of its own (see
    // WebAuthnConfig's own Javadoc for why one Account's id is enough, with no per-Organization
    // disambiguation needed).
    final UserIdentity user =
        UserIdentity.builder()
            .name(command.email())
            .displayName(command.email())
            .id(
                new ByteArray(
                    command.accountId().value().toString().getBytes(StandardCharsets.UTF_8)))
            .build();
    // The browser's "save a passkey for ..." prompt names the Organization the Account belongs to
    // (the consuming application), never Clavaris. With no name to show it shows the host instead.
    final String relyingPartyName =
        organizationNames.nameFor(command.organizationId()).orElse(null);
    return relyingParties
        .named(relyingPartyName)
        .startRegistration(StartRegistrationOptions.builder().user(user).build());
  }
}
