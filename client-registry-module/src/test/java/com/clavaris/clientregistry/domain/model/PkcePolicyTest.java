package com.clavaris.clientregistry.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class PkcePolicyTest {

  @Test
  void onlyRequiredIsEnforcedAtTheAuthorizationEndpoint() {
    assertThat(PkcePolicy.REQUIRED.enforced()).isTrue();
    assertThat(PkcePolicy.OPTIONAL.enforced()).isFalse();
  }

  @Test
  void aConfidentialClientMayHaveEitherPolicy() {
    assertThat(PkcePolicy.requireAllowedFor(true, PkcePolicy.REQUIRED))
        .isEqualTo(PkcePolicy.REQUIRED);
    assertThat(PkcePolicy.requireAllowedFor(true, PkcePolicy.OPTIONAL))
        .isEqualTo(PkcePolicy.OPTIONAL);
  }

  @Test
  void aPublicClientMustRequireIt() {
    assertThat(PkcePolicy.requireAllowedFor(false, PkcePolicy.REQUIRED))
        .isEqualTo(PkcePolicy.REQUIRED);
  }

  @Test
  void aPublicClientCanNeverHaveItOptionalWhoeverAsks() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PkcePolicy.requireAllowedFor(false, PkcePolicy.OPTIONAL))
        .withMessageContaining("only be optional for a confidential client");
  }

  @Test
  void aMissingPolicyIsRefusedRatherThanReadAsAnyValue() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PkcePolicy.requireAllowedFor(true, null))
        .withMessageContaining("must not be null");
  }
}
