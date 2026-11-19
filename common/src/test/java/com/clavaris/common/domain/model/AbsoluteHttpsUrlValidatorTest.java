package com.clavaris.common.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

/**
 * TD-ARCH-019: direct coverage for the shared validator extracted out of {@code
 * ClientBranding.validateLogoUrl}, {@code ClientDomainConfig.validateEmbeddingOriginIfPresent}, and
 * {@code WebhookEndpoint.requireValidUrl} — those three classes' own tests still cover the
 * behaviour through their own public API, this locks in the shared validator's own contract
 * directly instead of only indirectly through three different callers.
 */
class AbsoluteHttpsUrlValidatorTest {

  @Test
  void returnsTheValueUnchangedForAWellFormedAbsoluteHttpsUrl() {
    String result =
        AbsoluteHttpsUrlValidator.requireAbsoluteHttps("https://example.com/logo.png", "logoUrl");

    assertThat(result).isEqualTo("https://example.com/logo.png");
  }

  @Test
  void rejectsAMalformedUri() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> AbsoluteHttpsUrlValidator.requireAbsoluteHttps("not a url", "logoUrl"))
        .withMessageContaining("logoUrl")
        .withMessageContaining("well-formed");
  }

  @Test
  void rejectsARelativeUri() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> AbsoluteHttpsUrlValidator.requireAbsoluteHttps("/logo.png", "logoUrl"))
        .withMessageContaining("logoUrl")
        .withMessageContaining("absolute");
  }

  @Test
  void rejectsAPlainHttpUrl_noLoopbackException() {
    // Unlike OAuthClient's own redirect-URI validator, this class makes no exception for
    // localhost/127.0.0.1 — see this class's own Javadoc for why.
    assertThatIllegalArgumentException()
        .isThrownBy(
            () -> AbsoluteHttpsUrlValidator.requireAbsoluteHttps("http://localhost/x", "url"))
        .withMessageContaining("https");
  }

  @Test
  void rejectsANonHttpScheme() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                AbsoluteHttpsUrlValidator.requireAbsoluteHttps("ftp://example.com/file", "logoUrl"))
        .withMessageContaining("https");
  }

  @Test
  void schemeCheckIsCaseInsensitive() {
    String result =
        AbsoluteHttpsUrlValidator.requireAbsoluteHttps("HTTPS://example.com/logo.png", "logoUrl");

    assertThat(result).isEqualTo("HTTPS://example.com/logo.png");
  }

  // TD-ARCH-059: a TCP port is 1 to 65535; URI itself accepts any digits.
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "https://example.com:0/h",
        "https://example.com:65536/h",
        "https://example.com:99999/h",
        "https://example.com:2147483647/h"
      })
  void rejectsAPortOutsideOneToSixtyFiveThousandFiveHundredThirtyFive(final String url) {
    org.assertj.core.api.Assertions.assertThatIllegalArgumentException()
        .isThrownBy(() -> AbsoluteHttpsUrlValidator.requireAbsoluteHttps(url, "url"))
        .withMessageContaining("port");
  }

  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "https://example.com:1/h",
        "https://example.com:443/h",
        "https://example.com:8443/h",
        "https://example.com:65535/h",
        "https://example.com/h",
        "https://example.com:/h"
      })
  void acceptsAValidPortAndNoPortAtAll(final String url) {
    org.assertj.core.api.Assertions.assertThat(
            AbsoluteHttpsUrlValidator.requireAbsoluteHttps(url, "url"))
        .isEqualTo(url);
  }
}
