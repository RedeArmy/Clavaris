package com.clavaris.identity.infrastructure.adapter.out.webauthn;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.registerwebauthncredential.WebAuthnCredentialRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.WebAuthnCredential;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class YubicoCredentialRepositoryAdapterTest {

  private final WebAuthnCredentialRepository credentials = mock(WebAuthnCredentialRepository.class);
  private final YubicoCredentialRepositoryAdapter adapter =
      new YubicoCredentialRepositoryAdapter(credentials);

  private byte[] credentialIdBytes;
  private AccountId accountId;
  private ByteArray credentialId;

  @BeforeEach
  void setUp() {
    credentialIdBytes = "a-real-credential-id".getBytes(StandardCharsets.UTF_8);
    accountId = new AccountId(UUID.randomUUID());
    credentialId = new ByteArray(credentialIdBytes);
  }

  // Deliberately dead-by-design (this class's own Javadoc) — Clavaris never does a
  // username-first WebAuthn lookup, so these three always return an empty result regardless of
  // what the port says.
  @Test
  void getCredentialIdsForUsernameIsAlwaysEmpty() {
    assertThat(adapter.getCredentialIdsForUsername("anything")).isEmpty();
  }

  @Test
  void getUserHandleForUsernameIsAlwaysEmpty() {
    assertThat(adapter.getUserHandleForUsername("anything")).isEmpty();
  }

  @Test
  void getUsernameForUserHandleIsAlwaysEmpty() {
    assertThat(adapter.getUsernameForUserHandle(new ByteArray(new byte[] {1, 2, 3}))).isEmpty();
  }

  @Test
  void lookupReturnsTheMappedCredentialWhenFound() {
    WebAuthnCredential credential = registeredCredential();
    when(credentials.findByCredentialId(credentialIdBytes)).thenReturn(Optional.of(credential));

    Optional<RegisteredCredential> found = adapter.lookup(credentialId, new ByteArray(new byte[0]));

    assertThat(found).isPresent();
    assertThat(found.get().getCredentialId()).isEqualTo(credentialId);
    assertThat(found.get().getSignatureCount()).isEqualTo(credential.signatureCount());
    assertThat(found.get().getUserHandle().getBytes())
        .isEqualTo(accountId.value().toString().getBytes(StandardCharsets.UTF_8));
  }

  @Test
  void lookupIsEmptyWhenNoCredentialMatches() {
    when(credentials.findByCredentialId(credentialIdBytes)).thenReturn(Optional.empty());

    assertThat(adapter.lookup(credentialId, new ByteArray(new byte[0]))).isEmpty();
  }

  @Test
  void lookupAllReturnsASingleElementSetWhenFound() {
    WebAuthnCredential credential = registeredCredential();
    when(credentials.findByCredentialId(credentialIdBytes)).thenReturn(Optional.of(credential));

    assertThat(adapter.lookupAll(credentialId)).hasSize(1);
  }

  @Test
  void lookupAllIsAnEmptySetWhenNoCredentialMatches() {
    when(credentials.findByCredentialId(credentialIdBytes)).thenReturn(Optional.empty());

    assertThat(adapter.lookupAll(credentialId)).isEmpty();
  }

  private WebAuthnCredential registeredCredential() {
    return WebAuthnCredential.register(
        accountId,
        new OrganizationId(UUID.randomUUID()),
        credentialIdBytes,
        "a-public-key".getBytes(StandardCharsets.UTF_8),
        0,
        null,
        null);
  }
}
