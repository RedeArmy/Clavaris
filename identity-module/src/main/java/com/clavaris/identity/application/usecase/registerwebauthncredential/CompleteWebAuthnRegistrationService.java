package com.clavaris.identity.application.usecase.registerwebauthncredential;

import com.clavaris.identity.domain.model.WebAuthnCredential;
import com.yubico.webauthn.FinishRegistrationOptions;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.AuthenticatorAttestationResponse;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ClientRegistrationExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.exception.RegistrationFailedException;
import java.io.IOException;
import java.util.stream.Collectors;

// PMD.LawOfDemeter: reading fields off Yubico's own
// RegistrationResult/PublicKeyCredentialDescriptor
// DTOs is the entire point of this class — same "working with a vetted library's own result type"
// rationale as every other adapter touching this library's data types.
@SuppressWarnings("PMD.LawOfDemeter")
public class CompleteWebAuthnRegistrationService implements CompleteWebAuthnRegistrationUseCase {

  private final RelyingParty relyingParty;
  private final WebAuthnCredentialRepository credentials;

  public CompleteWebAuthnRegistrationService(
      final RelyingParty relyingParty, final WebAuthnCredentialRepository credentials) {
    this.relyingParty = relyingParty;
    this.credentials = credentials;
  }

  @Override
  public void handle(final CompleteWebAuthnRegistrationCommand command) {
    final PublicKeyCredential<AuthenticatorAttestationResponse, ClientRegistrationExtensionOutputs>
        response;
    try {
      response = PublicKeyCredential.parseRegistrationResponseJson(command.credentialJson());
    } catch (final IOException e) {
      throw new InvalidWebAuthnRegistrationException("Malformed WebAuthn registration response", e);
    }

    final RegistrationResult result;
    try {
      result =
          relyingParty.finishRegistration(
              FinishRegistrationOptions.builder()
                  .request(command.request())
                  .response(response)
                  .build());
    } catch (final RegistrationFailedException e) {
      throw new InvalidWebAuthnRegistrationException("WebAuthn registration ceremony failed", e);
    }

    final String transports =
        result
            .getKeyId()
            .getTransports()
            .map(
                values ->
                    values.stream()
                        .map(AuthenticatorTransport::getId)
                        .collect(Collectors.joining(",")))
            .orElse(null);

    credentials.insert(
        WebAuthnCredential.register(
            command.accountId(),
            command.organizationId(),
            result.getKeyId().getId().getBytes(),
            result.getPublicKeyCose().getBytes(),
            result.getSignatureCount(),
            transports,
            command.nickname()));
  }
}
