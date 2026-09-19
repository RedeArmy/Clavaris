package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class AdminCreateAccountFormTest {

  @Test
  void gettersReturnWhatSettersStored() {
    AdminCreateAccountForm form = new AdminCreateAccountForm();

    form.setFirstName("Ada");
    form.setLastName("Lovelace");
    form.setEmail("ada@example.com");
    form.setUsername("adalovelace");
    form.setPassword("a-valid-password");
    form.setPhoneNumber("+15550001111");
    form.setIgnorePasswordPolicy(true);
    form.setIgnoreAccessRestrictions(true);

    assertThat(form.getFirstName()).isEqualTo("Ada");
    assertThat(form.getLastName()).isEqualTo("Lovelace");
    assertThat(form.getEmail()).isEqualTo("ada@example.com");
    assertThat(form.getUsername()).isEqualTo("adalovelace");
    assertThat(form.getPassword()).isEqualTo("a-valid-password");
    assertThat(form.getPhoneNumber()).isEqualTo("+15550001111");
    assertThat(form.isIgnorePasswordPolicy()).isTrue();
    assertThat(form.isIgnoreAccessRestrictions()).isTrue();
  }

  // BR-ID-01: never the raw password — same rationale RegisterAccountForm's own identical test
  // documents for its sibling override.
  @Test
  void toStringNeverIncludesThePassword() {
    AdminCreateAccountForm form = new AdminCreateAccountForm();
    form.setEmail("ada@example.com");
    form.setUsername("adalovelace");
    form.setPassword("super-secret-password");

    assertThat(form.toString())
        .contains("ada@example.com")
        .contains("adalovelace")
        .doesNotContain("super-secret-password");
  }
}
