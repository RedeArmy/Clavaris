package com.clavaris.identity.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class EmailOneTimeCodeTest {

  private static final Pattern SIX_DIGITS = Pattern.compile("^\\d{6}$");

  @Test
  void generatesExactlySixDigitsZeroPadded() {
    for (int i = 0; i < 200; i++) {
      String code = EmailOneTimeCode.generate();
      assertThat(code).matches(SIX_DIGITS);
    }
  }

  @Test
  void generatesDifferentValuesAcrossCalls() {
    Set<String> generated = new HashSet<>();
    for (int i = 0; i < 50; i++) {
      generated.add(EmailOneTimeCode.generate());
    }

    assertThat(generated)
        .as(
            "50 draws from a 1,000,000-value space colliding down to a handful would indicate a"
                + " broken generator, not bad luck")
        .hasSizeGreaterThan(40);
  }
}
