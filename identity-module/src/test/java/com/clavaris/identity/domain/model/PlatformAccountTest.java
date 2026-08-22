package com.clavaris.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.time.Instant;
import org.junit.jupiter.api.Test;

class PlatformAccountTest {

  private final Email email = new Email("founder@example.com");

  @Test
  void registerCreatesAnActiveAccountWithNoCredentialYet() {
    PlatformAccount account = PlatformAccount.register(email);

    assertThat(account.email()).isEqualTo(email);
    assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
    assertThat(account.emailVerifiedAt()).isEmpty();
    assertThat(account.passwordCredential()).isEmpty();
  }

  @Test
  void eachRegistrationGetsAUniqueId() {
    PlatformAccount first = PlatformAccount.register(email);
    PlatformAccount second = PlatformAccount.register(new Email("someone-else@example.com"));

    assertThat(first.id()).isNotEqualTo(second.id());
  }

  @Test
  void attachPasswordCredentialGivesTheAccountAWorkingAuthMethod() {
    PlatformAccount account = PlatformAccount.register(email);

    account.attachPasswordCredential("argon2id$hashed-value");

    assertThat(account.passwordCredential()).isPresent();
    assertThat(account.passwordCredential().orElseThrow().platformAccountId())
        .isEqualTo(account.id());
  }

  @Test
  void cannotAttachASecondPasswordCredentialAtRegistrationTime() {
    PlatformAccount account = PlatformAccount.register(email);
    account.attachPasswordCredential("first-hash");

    assertThatIllegalStateException()
        .isThrownBy(() -> account.attachPasswordCredential("second-hash"));
  }

  @Test
  void verifyEmailSetsTheTimestampOnceAndIsIdempotentOnASecondCall() {
    PlatformAccount account = PlatformAccount.register(email);

    account.verifyEmail();
    Instant firstVerifiedAt = account.emailVerifiedAt().orElseThrow();
    account.verifyEmail();

    assertThat(account.emailVerifiedAt()).contains(firstVerifiedAt);
  }

  @Test
  void resetPasswordCredentialUpdatesTheExistingCredentialsHashInPlace() {
    PlatformAccount account = PlatformAccount.register(email);
    account.attachPasswordCredential("old-hash");
    PlatformPasswordCredential original = account.passwordCredential().orElseThrow();

    account.resetPasswordCredential("new-hash");

    PlatformPasswordCredential replaced = account.passwordCredential().orElseThrow();
    assertThat(replaced.passwordHash()).isEqualTo("new-hash");
    assertThat(replaced.id()).isEqualTo(original.id());
  }

  @Test
  void resetPasswordCredentialRejectsAnAccountWithNoExistingCredential() {
    PlatformAccount account = PlatformAccount.register(email);

    assertThatIllegalStateException().isThrownBy(() -> account.resetPasswordCredential("hash"));
  }

  @Test
  void reconstitutePreservesTheRealPersistedFieldsAndAttachedCredential() {
    PlatformAccountId id = PlatformAccountId.newId();
    Instant createdAt = Instant.parse("2026-01-01T00:00:00Z");
    Instant emailVerifiedAt = Instant.parse("2026-01-02T00:00:00Z");
    PlatformPasswordCredential credential =
        PlatformPasswordCredential.reconstitute(
            java.util.UUID.randomUUID(),
            id,
            "argon2id$stored-hash",
            Instant.parse("2026-01-03T00:00:00Z"));

    PlatformAccount account =
        PlatformAccount.reconstitute(
            id, email, createdAt, emailVerifiedAt, AccountStatus.SUSPENDED, credential);

    assertThat(account.id()).isEqualTo(id);
    assertThat(account.email()).isEqualTo(email);
    assertThat(account.createdAt()).isEqualTo(createdAt);
    assertThat(account.emailVerifiedAt()).contains(emailVerifiedAt);
    assertThat(account.status()).isEqualTo(AccountStatus.SUSPENDED);
    assertThat(account.passwordCredential()).contains(credential);
  }
}
