package com.clavaris.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

import org.junit.jupiter.api.Test;

class UsernameTest {

  @Test
  void normalizesToLowerCaseAndTrimsWhitespace() {
    Username username = new Username("  Some_User-42  ");

    assertThat(username.value()).isEqualTo("some_user-42");
  }

  @Test
  void rejectsAnEmptyValue() {
    assertThatIllegalArgumentException().isThrownBy(() -> new Username(""));
  }

  @Test
  void rejectsAValueBelowTheMinimumLength() {
    assertThatIllegalArgumentException().isThrownBy(() -> new Username("ab"));
  }

  @Test
  void acceptsAValueAtTheMinimumLength() {
    assertThat(new Username("abc").value()).isEqualTo("abc");
  }

  @Test
  void rejectsAValueAboveTheMaximumLength() {
    assertThatIllegalArgumentException().isThrownBy(() -> new Username("a".repeat(33)));
  }

  @Test
  void rejectsAnAtSignSoAnEmailShapedValueIsNeverAlsoAValidUsername() {
    // LoginController's own single-identifier-field routing relies on this disjointness.
    assertThatIllegalArgumentException().isThrownBy(() -> new Username("user@example.com"));
  }

  @Test
  void rejectsWhitespaceInsideTheValue() {
    assertThatIllegalArgumentException().isThrownBy(() -> new Username("has space"));
  }

  @Test
  void rejectsANullValue() {
    assertThatNullPointerException().isThrownBy(() -> new Username(null));
  }
}
