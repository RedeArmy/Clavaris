package com.clavaris.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class PasswordCredentialTest {

  private final AccountId accountId = new AccountId(UUID.randomUUID());

  @Test
  void issueCarriesTheGivenHashAndAccountId() {
    PasswordCredential credential = PasswordCredential.issue(accountId, "argon2id$...");

    assertThat(credential.accountId()).isEqualTo(accountId);
    assertThat(credential.passwordHash()).isEqualTo("argon2id$...");
    assertThat(credential.updatedAt()).isNotNull();
  }

  @Test
  void rejectsABlankHash() {
    // A hasher bug producing an empty hash must fail loudly here, not silently reach persistence
    // as an account nothing (and everything) authenticates against.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PasswordCredential.issue(accountId, "  "));
  }

  @Test
  void reconstitutePreservesTheRealPersistedIdAndUpdatedAt() {
    UUID id = UUID.randomUUID();
    Instant updatedAt = Instant.parse("2026-08-01T00:00:00Z");

    PasswordCredential credential =
        PasswordCredential.reconstitute(id, accountId, "argon2id$stored-hash", updatedAt);

    assertThat(credential.id()).isEqualTo(id);
    assertThat(credential.accountId()).isEqualTo(accountId);
    assertThat(credential.passwordHash()).isEqualTo("argon2id$stored-hash");
    assertThat(credential.updatedAt()).isEqualTo(updatedAt);
  }
}
