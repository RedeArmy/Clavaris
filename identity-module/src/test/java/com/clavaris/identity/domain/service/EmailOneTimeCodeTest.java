package com.clavaris.identity.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class EmailOneTimeCodeTest {

  // 2-9 and A-Z without I and O: the 32 symbols of ADR-0034.
  private static final Pattern A_CODE = Pattern.compile("^[2-9A-HJ-NP-Z]{6}$");
  private static final String EVERY_SYMBOL = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ";

  @Test
  void generatesSixCharactersFromTheThirtyTwoSymbolAlphabet() {
    for (int i = 0; i < 2_000; i++) {
      assertThat(EmailOneTimeCode.generate()).matches(A_CODE);
    }
  }

  // The point of the alphabet: no pair a person can mistake for another. 0 and 1 are not there, and
  // neither are the letters they are confused with.
  @Test
  void neverContainsACharacterThatCanBeMistakenForAnother() {
    for (int i = 0; i < 5_000; i++) {
      assertThat(EmailOneTimeCode.generate()).doesNotContainPattern("[01OI]");
    }
  }

  @Test
  void isAlwaysCapitalLettersAndDigitsSoThereIsNoCaseToGetWrong() {
    for (int i = 0; i < 2_000; i++) {
      String code = EmailOneTimeCode.generate();
      assertThat(code).isEqualTo(code.toUpperCase(java.util.Locale.ROOT));
    }
  }

  // Thirty bits needs every symbol to be reachable and none favoured: 32 divides the generator's
  // range exactly, so there is no modulo bias, and over many draws each symbol shows up about as
  // often as any other.
  @Test
  void usesEverySymbolOfTheAlphabetAboutEquallyOften() {
    int draws = 20_000;
    int[] counts = new int[128];
    for (int i = 0; i < draws; i++) {
      for (char c : EmailOneTimeCode.generate().toCharArray()) {
        counts[c]++;
      }
    }
    double expected = draws * 6.0 / EVERY_SYMBOL.length();
    for (char symbol : EVERY_SYMBOL.toCharArray()) {
      assertThat((double) counts[symbol])
          .as("occurrences of %s", symbol)
          .isBetween(expected * 0.9, expected * 1.1);
    }
  }

  @Test
  void generatesDifferentValuesAcrossCalls() {
    Set<String> generated = new HashSet<>();
    for (int i = 0; i < 1_000; i++) {
      generated.add(EmailOneTimeCode.generate());
    }

    assertThat(generated)
        .as("1,000 draws from a 1,073,741,824-value space should essentially never collide")
        .hasSize(1_000);
  }

  @Test
  void normalizeAcceptsWhatPeopleActuallyType() {
    assertThat(EmailOneTimeCode.normalize("ab2c3d")).isEqualTo("AB2C3D");
    assertThat(EmailOneTimeCode.normalize(" ab2 c3d ")).isEqualTo("AB2C3D");
    assertThat(EmailOneTimeCode.normalize("AB2-C3D")).isEqualTo("AB2C3D");
    assertThat(EmailOneTimeCode.normalize("ab2–c3d"))
        .as("an en dash from a copy")
        .isEqualTo("AB2C3D");
    assertThat(EmailOneTimeCode.normalize("\tab2\nc3d")).isEqualTo("AB2C3D");
  }

  // A code issued before ADR-0034 was six digits; one still in flight must keep working.
  @Test
  void normalizeLeavesAnOlderDigitCodeAsItWas() {
    assertThat(EmailOneTimeCode.normalize("482913")).isEqualTo("482913");
    assertThat(EmailOneTimeCode.normalize("042 817")).isEqualTo("042817");
  }

  @Test
  void normalizeOfNothingIsEmptyNotAnError() {
    assertThat(EmailOneTimeCode.normalize(null)).isEmpty();
    assertThat(EmailOneTimeCode.normalize("   ")).isEmpty();
  }

  @Test
  void normalizeDoesNotInventAValidCodeOutOfSomethingElse() {
    assertThat(EmailOneTimeCode.normalize("o0il1"))
        .as("only case and separators change")
        .isEqualTo("O0IL1");
    assertThat(EmailOneTimeCode.normalize("O0IL1")).doesNotMatch(A_CODE);
  }
}
