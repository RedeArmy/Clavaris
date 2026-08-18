package com.clavaris.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccountTest {

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());
  private final Email email = new Email("new-user@example.com");

  @Test
  void registerCreatesAnActiveAccountWithNoCredentialYet() {
    // BR-ID-02: registration itself never attaches a credential — the use case does that as a
    // separate, explicit step, so "an Account with no credential" is a real, valid intermediate
    // state the factory can produce, not something a caller could accidentally skip.
    Account account = Account.register(organizationId, email);

    assertThat(account.organizationId()).isEqualTo(organizationId);
    assertThat(account.email()).isEqualTo(email);
    assertThat(account.status()).isEqualTo(AccountStatus.ACTIVE);
    assertThat(account.emailVerifiedAt()).isEmpty();
    assertThat(account.passwordCredential()).isEmpty();
  }

  @Test
  void eachRegistrationGetsAUniqueId() {
    Account first = Account.register(organizationId, email);
    Account second = Account.register(organizationId, new Email("someone-else@example.com"));

    assertThat(first.id()).isNotEqualTo(second.id());
  }

  @Test
  void attachPasswordCredentialGivesTheAccountAWorkingAuthMethod() {
    Account account = Account.register(organizationId, email);

    account.attachPasswordCredential("argon2id$hashed-value");

    assertThat(account.passwordCredential()).isPresent();
    assertThat(account.passwordCredential().orElseThrow().accountId()).isEqualTo(account.id());
    assertThat(account.passwordCredential().orElseThrow().passwordHash())
        .isEqualTo("argon2id$hashed-value");
  }

  @Test
  void cannotAttachASecondPasswordCredentialAtRegistrationTime() {
    // Deliberately not a password-change flow (that's a separate future use case with its own
    // invariants, e.g. requiring the current password) — attaching twice here is always a bug.
    Account account = Account.register(organizationId, email);
    account.attachPasswordCredential("first-hash");

    assertThatIllegalStateException()
        .isThrownBy(() -> account.attachPasswordCredential("second-hash"));
  }
}
