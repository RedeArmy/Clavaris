package com.clavaris.identity.application.usecase.registerwebauthncredential;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.WebAuthnCredential;
import com.yubico.webauthn.RegistrationResult;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.AuthenticatorTransport;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialCreationOptions;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import com.yubico.webauthn.exception.RegistrationFailedException;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/**
 * {@link RegistrationResult} is a {@code final} Yubico DTO with no public builder — unmockable via
 * a subclass proxy, but a real {@code mock(...)} stand-in works fine under this project's own
 * inline mock-maker (same precedent {@code AuthenticateWithWebAuthnServiceTest} already establishes
 * for its sibling {@code AssertionResult}). {@link PublicKeyCredentialDescriptor} does have a
 * public builder, used directly for {@code getKeyId()}'s own return value. A full successful
 * ceremony still needs a real, cryptographically valid attestation — not reproduced here — but
 * {@code finishRegistration} itself is mocked, so the real success path (transports joining, {@code
 * credentials.insert} with every field correctly mapped) is genuinely exercised against a
 * structurally valid (not cryptographically valid) attestation response JSON.
 */
class CompleteWebAuthnRegistrationServiceTest {

  // A syntactically valid WebAuthn attestation-response JSON (a "none"-format CBOR
  // attestationObject, hand-encoded): PublicKeyCredential.parseRegistrationResponseJson only
  // validates shape (authData must be >=37 bytes), never the signature/attestation trust chain —
  // that check happens inside the (here, mocked) RelyingParty#finishRegistration.
  private static final String VALID_SHAPED_ATTESTATION_JSON =
      "{\"type\":\"public-key\",\"id\":\"AQIDBA\",\"rawId\":\"AQIDBA\",\"response\":{"
          + "\"attestationObject\":\"o2NmbXRkbm9uZWdhdHRTdG10oGhhdXRoRGF0YVglAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA\","
          + "\"clientDataJSON\":\"eyJ0eXBlIjoid2ViYXV0aG4uY3JlYXRlIiwiY2hhbGxlbmdlIjoiQVFJREJBIiwib3JpZ2luIjoiaHR0cHM6Ly9leGFtcGxlLmNvbSJ9\"},"
          + "\"clientExtensionResults\":{}}";

  private final RelyingParty relyingParty = mock(RelyingParty.class);
  private final WebAuthnCredentialRepository credentials = mock(WebAuthnCredentialRepository.class);
  private final CompleteWebAuthnRegistrationService service =
      new CompleteWebAuthnRegistrationService(relyingParty, credentials);

  private final AccountId accountId = AccountId.newId();
  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  private CompleteWebAuthnRegistrationCommand commandWithNickname(final String nickname) {
    return new CompleteWebAuthnRegistrationCommand(
        accountId,
        organizationId,
        mock(PublicKeyCredentialCreationOptions.class),
        VALID_SHAPED_ATTESTATION_JSON,
        nickname);
  }

  @Test
  void aMalformedCredentialJsonNeverReachesTheRelyingPartyOrTheRepository()
      throws RegistrationFailedException {
    CompleteWebAuthnRegistrationCommand command =
        new CompleteWebAuthnRegistrationCommand(
            AccountId.newId(),
            new OrganizationId(UUID.randomUUID()),
            mock(PublicKeyCredentialCreationOptions.class),
            "not valid json",
            null);

    assertThatThrownBy(() -> service.handle(command))
        .isInstanceOf(InvalidWebAuthnRegistrationException.class);

    verifyNoInteractions(credentials);
    verify(relyingParty, never()).finishRegistration(any());
  }

  @Test
  void aFailedCeremonyNeverReachesTheRepository() throws RegistrationFailedException {
    when(relyingParty.finishRegistration(any()))
        .thenThrow(new RegistrationFailedException(new IllegalArgumentException("rejected")));

    assertThatThrownBy(() -> service.handle(commandWithNickname(null)))
        .isInstanceOf(InvalidWebAuthnRegistrationException.class);

    verifyNoInteractions(credentials);
  }

  @Test
  void aSuccessfulCeremonyInsertsACredentialWithEveryFieldMapped()
      throws RegistrationFailedException {
    byte[] rawCredentialId = "a-real-credential-id".getBytes(StandardCharsets.UTF_8);
    byte[] publicKeyCose = "a-public-key".getBytes(StandardCharsets.UTF_8);
    SortedSet<AuthenticatorTransport> transports =
        new TreeSet<>(
            java.util.List.of(AuthenticatorTransport.INTERNAL, AuthenticatorTransport.HYBRID));
    PublicKeyCredentialDescriptor keyId =
        PublicKeyCredentialDescriptor.builder()
            .id(new ByteArray(rawCredentialId))
            .transports(transports)
            .build();
    RegistrationResult result = mock(RegistrationResult.class);
    when(result.getKeyId()).thenReturn(keyId);
    when(result.getPublicKeyCose()).thenReturn(new ByteArray(publicKeyCose));
    when(result.getSignatureCount()).thenReturn(0L);
    when(relyingParty.finishRegistration(any())).thenReturn(result);

    service.handle(commandWithNickname("My passkey"));

    ArgumentCaptor<WebAuthnCredential> captor = ArgumentCaptor.forClass(WebAuthnCredential.class);
    verify(credentials).insert(captor.capture());
    WebAuthnCredential inserted = captor.getValue();
    assertThat(inserted.accountId()).isEqualTo(accountId);
    assertThat(inserted.organizationId()).isEqualTo(organizationId);
    assertThat(inserted.credentialId()).isEqualTo(rawCredentialId);
    assertThat(inserted.publicKeyCose()).isEqualTo(publicKeyCose);
    assertThat(inserted.nickname()).isEqualTo("My passkey");
    // AuthenticatorTransport#getId() values, comma-joined — see the real class's own mapping.
    assertThat(inserted.transports()).contains("internal").contains("hybrid").contains(",");
  }

  @Test
  void aSuccessfulCeremonyWithNoTransportsReportedStoresNullTransports()
      throws RegistrationFailedException {
    PublicKeyCredentialDescriptor keyId =
        PublicKeyCredentialDescriptor.builder()
            .id(new ByteArray("credential-no-transports".getBytes(StandardCharsets.UTF_8)))
            .transports(Optional.empty())
            .build();
    RegistrationResult result = mock(RegistrationResult.class);
    when(result.getKeyId()).thenReturn(keyId);
    when(result.getPublicKeyCose())
        .thenReturn(new ByteArray("a-public-key".getBytes(StandardCharsets.UTF_8)));
    when(result.getSignatureCount()).thenReturn(0L);
    when(relyingParty.finishRegistration(any())).thenReturn(result);

    service.handle(commandWithNickname(null));

    ArgumentCaptor<WebAuthnCredential> captor = ArgumentCaptor.forClass(WebAuthnCredential.class);
    verify(credentials).insert(captor.capture());
    assertThat(captor.getValue().transports()).isNull();
    assertThat(captor.getValue().nickname()).isNull();
  }
}
