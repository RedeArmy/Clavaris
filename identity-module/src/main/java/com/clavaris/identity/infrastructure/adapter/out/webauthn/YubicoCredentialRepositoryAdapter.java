package com.clavaris.identity.infrastructure.adapter.out.webauthn;

import com.clavaris.identity.application.usecase.registerwebauthncredential.WebAuthnCredentialRepository;
import com.clavaris.identity.domain.model.WebAuthnCredential;
import com.yubico.webauthn.CredentialRepository;
import com.yubico.webauthn.RegisteredCredential;
import com.yubico.webauthn.data.ByteArray;
import com.yubico.webauthn.data.PublicKeyCredentialDescriptor;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Bridges Yubico's own {@link CredentialRepository} callback interface (the lookups {@link
 * com.yubico.webauthn.RelyingParty} needs internally) onto Clavaris's own {@link
 * WebAuthnCredentialRepository} port. Clavaris never does a username-first WebAuthn lookup —
 * registration/authentication are both keyed off the already-authenticated/to-be-resolved {@code
 * AccountId} directly (discoverable/resident-key flow, decided for this feature, see {@code
 * StartWebAuthnAuthenticationService}'s own Javadoc) — so the two {@code *ForUsername} methods
 * below are dead code paths by design, not an incomplete implementation; Yubico's own {@code
 * RelyingParty.startAssertion} only calls them when a caller supplies a username, which this
 * codebase's own {@code StartAssertionOptions.builder().build()} call never does.
 */
@Component
class YubicoCredentialRepositoryAdapter implements CredentialRepository {

  private final WebAuthnCredentialRepository credentials;

  /* package */ YubicoCredentialRepositoryAdapter(final WebAuthnCredentialRepository credentials) {
    this.credentials = credentials;
  }

  @Override
  public Set<PublicKeyCredentialDescriptor> getCredentialIdsForUsername(final String username) {
    return Set.of();
  }

  @Override
  public Optional<ByteArray> getUserHandleForUsername(final String username) {
    return Optional.empty();
  }

  @Override
  public Optional<String> getUsernameForUserHandle(final ByteArray userHandle) {
    return Optional.empty();
  }

  @Override
  public Optional<RegisteredCredential> lookup(
      final ByteArray credentialId, final ByteArray userHandle) {
    return credentials
        .findByCredentialId(credentialId.getBytes())
        .map(YubicoCredentialRepositoryAdapter::toRegisteredCredential);
  }

  @Override
  public Set<RegisteredCredential> lookupAll(final ByteArray credentialId) {
    return credentials
        .findByCredentialId(credentialId.getBytes())
        .map(YubicoCredentialRepositoryAdapter::toRegisteredCredential)
        .map(Set::of)
        .orElseGet(Set::of);
  }

  private static RegisteredCredential toRegisteredCredential(final WebAuthnCredential credential) {
    return RegisteredCredential.builder()
        .credentialId(new ByteArray(credential.credentialId()))
        .userHandle(
            new ByteArray(
                credential.accountId().value().toString().getBytes(StandardCharsets.UTF_8)))
        .publicKeyCose(new ByteArray(credential.publicKeyCose()))
        .signatureCount(credential.signatureCount())
        .build();
  }
}
