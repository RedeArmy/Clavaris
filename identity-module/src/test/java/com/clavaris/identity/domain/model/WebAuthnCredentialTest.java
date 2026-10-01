package com.clavaris.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WebAuthnCredentialTest {

  private final AccountId accountId = new AccountId(UUID.randomUUID());
  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  @Test
  void registerCarriesTheGivenFieldsAndStartsWithNoLastUsedAt() {
    WebAuthnCredential credential =
        WebAuthnCredential.register(
            accountId,
            organizationId,
            new byte[] {1, 2, 3},
            new byte[] {4, 5, 6},
            0L,
            "usb,nfc",
            "My key");

    assertThat(credential.accountId()).isEqualTo(accountId);
    assertThat(credential.organizationId()).isEqualTo(organizationId);
    assertThat(credential.credentialId()).containsExactly(1, 2, 3);
    assertThat(credential.publicKeyCose()).containsExactly(4, 5, 6);
    assertThat(credential.signatureCount()).isZero();
    assertThat(credential.transports()).isEqualTo("usb,nfc");
    assertThat(credential.nickname()).isEqualTo("My key");
    assertThat(credential.lastUsedAt()).isNull();
  }

  @Test
  void touchAdvancesSignatureCountAndSetsLastUsedAtWithoutAffectingCreatedAt() {
    WebAuthnCredential credential =
        WebAuthnCredential.register(
            accountId, organizationId, new byte[] {1}, new byte[] {2}, 0L, null, null);
    Instant originalCreatedAt = credential.createdAt();

    credential.touch(5L);

    assertThat(credential.signatureCount()).isEqualTo(5L);
    assertThat(credential.lastUsedAt()).isNotNull();
    assertThat(credential.createdAt()).isEqualTo(originalCreatedAt);
  }

  @Test
  void reconstituteKeepsTheRealPersistedIdRatherThanMintingANewOne() {
    UUID persistedId = UUID.randomUUID();
    Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
    Instant lastUsedAt = Instant.parse("2026-01-02T00:00:00Z");

    WebAuthnCredential credential =
        WebAuthnCredential.reconstitute(
            persistedId,
            accountId,
            organizationId,
            new byte[] {9},
            new byte[] {8},
            3L,
            "usb",
            "Nickname",
            createdAt,
            lastUsedAt);

    assertThat(credential.id()).isEqualTo(persistedId);
    assertThat(credential.signatureCount()).isEqualTo(3L);
    assertThat(credential.createdAt()).isEqualTo(createdAt);
    assertThat(credential.lastUsedAt()).isEqualTo(lastUsedAt);
  }

  // Defense-in-depth: the byte[] accessors must never hand back the live internal array —
  // same "accessors clone, never expose the mutable internal array" convention the constructor's
  // own defensive clone already establishes for the inbound side.
  @Test
  void credentialIdAndPublicKeyCoseAccessorsReturnDefensiveCopies() {
    byte[] originalCredentialId = {1, 2, 3};
    WebAuthnCredential credential =
        WebAuthnCredential.register(
            accountId, organizationId, originalCredentialId, new byte[] {4}, 0L, null, null);

    credential.credentialId()[0] = 99;

    assertThat(credential.credentialId()).containsExactly(1, 2, 3);
  }
}
