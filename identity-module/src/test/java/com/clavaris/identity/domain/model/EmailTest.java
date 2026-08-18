package com.clavaris.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class EmailTest {

  @Test
  void normalizesCaseAndWhitespace() {
    // data-model.md §3: (organizationId, email) uniqueness must not be defeatable by case or
    // whitespace — normalization is what makes that true.
    Email email = new Email("  Someone@Example.COM  ");

    assertThat(email.value()).isEqualTo("someone@example.com");
  }

  @Test
  void rejectsMissingAtSign() {
    assertThatIllegalArgumentException().isThrownBy(() -> new Email("not-an-email"));
  }

  @Test
  void rejectsMissingDomain() {
    assertThatIllegalArgumentException().isThrownBy(() -> new Email("someone@"));
  }

  @Test
  void rejectsBlank() {
    assertThatIllegalArgumentException().isThrownBy(() -> new Email("   "));
  }

  @Test
  void rejectsNull() {
    assertThatNullPointerException().isThrownBy(() -> new Email(null));
  }
}
