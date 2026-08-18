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

  @Test
  void acceptsAtTheRfc5321MaximumLength() {
    // "a...a@b...b.com" sized to exactly 254 chars total.
    String local = "a".repeat(64);
    String domain = "b".repeat(254 - local.length() - 1 - ".com".length()) + ".com";
    String atMaxLength = local + "@" + domain;
    assertThat(atMaxLength).hasSize(254);

    assertThat(new Email(atMaxLength).value()).isEqualTo(atMaxLength);
  }

  @Test
  void rejectsAboveTheRfc5321MaximumLength() {
    String local = "a".repeat(64);
    String domain = "b".repeat(255 - local.length() - 1 - ".com".length()) + ".com";
    String tooLong = local + "@" + domain;
    assertThat(tooLong).hasSize(255);

    assertThatIllegalArgumentException().isThrownBy(() -> new Email(tooLong));
  }

  @Test
  void rejectsAPathologicallyLongInputWithoutStackOverflow() {
    // A pathological input (many dot-separated labels) must be rejected on length alone, before
    // ever reaching the regex — proves the length guard actually short-circuits, not just that a
    // long input happens to fail the pattern too.
    String manyLabels = "a@" + "b.".repeat(500_000);

    assertThatIllegalArgumentException().isThrownBy(() -> new Email(manyLabels));
  }
}
