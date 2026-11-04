package com.clavaris.identity.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class TotpAlgorithmTest {

  // RFC 6238 appendix B, the SHA-1 secret. The RFC lists eight digits; six are the last six.
  private static final byte[] RFC_SECRET =
      "12345678901234567890".getBytes(StandardCharsets.US_ASCII);

  @ParameterizedTest
  @CsvSource({
    "59, 287082",
    "1111111109, 081804",
    "1111111111, 050471",
    "1234567890, 005924",
    "2000000000, 279037",
    "20000000000, 353130"
  })
  void matchesTheRfc6238TestVectors(final long epochSeconds, final String expected) {
    assertThat(TotpAlgorithm.codeAt(RFC_SECRET, TotpAlgorithm.stepAt(epochSeconds)))
        .isEqualTo(expected);
  }

  @Test
  void aCodeIsAcceptedInItsOwnStepAndOneEitherSideButNotFurther() {
    final String code = TotpAlgorithm.codeAt(RFC_SECRET, TotpAlgorithm.stepAt(1_111_111_109L));
    final long now = 1_111_111_109L;

    assertThat(TotpAlgorithm.matchingStep(RFC_SECRET, code, now, 1))
        .hasValue(TotpAlgorithm.stepAt(now));
    assertThat(TotpAlgorithm.matchingStep(RFC_SECRET, code, now + 30, 1))
        .hasValue(TotpAlgorithm.stepAt(now));
    assertThat(TotpAlgorithm.matchingStep(RFC_SECRET, code, now - 30, 1))
        .hasValue(TotpAlgorithm.stepAt(now));
    assertThat(TotpAlgorithm.matchingStep(RFC_SECRET, code, now + 90, 1))
        .isEqualTo(OptionalLong.empty());
  }

  @Test
  void aWrongCodeMatchesNothing() {
    assertThat(TotpAlgorithm.matchingStep(RFC_SECRET, "000000", 1_111_111_109L, 1)).isEmpty();
  }

  @Test
  void onlySixAsciiDigitsLookLikeACode() {
    assertThat(TotpAlgorithm.looksLikeCode("123456")).isTrue();
    assertThat(TotpAlgorithm.looksLikeCode("12345")).isFalse();
    assertThat(TotpAlgorithm.looksLikeCode("1234567")).isFalse();
    assertThat(TotpAlgorithm.looksLikeCode("12345a")).isFalse();
    assertThat(TotpAlgorithm.looksLikeCode("١٢٣٤٥٦")).isFalse();
    assertThat(TotpAlgorithm.looksLikeCode(null)).isFalse();
  }

  @Test
  void aNewSecretIs160BitsAndNeverRepeats() {
    final byte[] first = TotpAlgorithm.newSecret();
    assertThat(first).hasSize(20);
    assertThat(TotpAlgorithm.newSecret()).isNotEqualTo(first);
  }

  @Test
  void theUriCarriesTheSecretIssuerAndTheDefaultsEveryAppUnderstands() {
    final String uri = TotpAlgorithm.otpauthUri("Acme & Co", "ana@example.com", "JBSWY3DPEHPK3PXP");

    assertThat(uri)
        .startsWith("otpauth://totp/Acme%20%26%20Co:ana%40example.com?")
        .contains("secret=JBSWY3DPEHPK3PXP")
        .contains("issuer=Acme%20%26%20Co")
        .contains("algorithm=SHA1")
        .contains("digits=6")
        .contains("period=30");
  }
}
