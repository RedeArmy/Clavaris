package com.clavaris.webhook.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class WebhookUrlSsrfCheckerTest {

  private final WebhookUrlSsrfChecker checker = new WebhookUrlSsrfChecker();

  @Test
  void allowsAnOrdinaryPublicHttpsUrl() {
    // 8.8.8.8 (Google Public DNS) — a real, stable, well-known public address, not a live network
    // call: InetAddress.getAllByName parses a literal IP address directly, no actual DNS lookup.
    SsrfCheckResult result = checker.check("https://8.8.8.8/webhooks");

    assertThat(result.safe()).isTrue();
    assertThat(result.reason()).isNull();
  }

  // Every disallowed address category this class rejects — loopback (IPv4/IPv6), link-local
  // (169.254.169.254, the single most concrete real-world consequence of this finding, TD-SEC-053's
  // own register wording), RFC 1918 private, IPv6 unique-local (fc00::/7, RFC 4193 — the modern
  // range isSiteLocalAddress() alone does not recognise, only the deprecated fec0::/10, this
  // class's own documented gap fix), multicast, and the wildcard address — collapsed into one
  // parameterized test rather than a near-identical `@Test` per category.
  @ParameterizedTest(name = "{0} is blocked as unsafe ({1})")
  @CsvSource({
    "https://127.0.0.1/webhooks, loopback",
    "https://[::1]/webhooks, loopback",
    "https://169.254.169.254/latest/meta-data/, link-local",
    "https://10.0.0.5/hooks, private",
    "https://172.16.0.5/hooks, private",
    "https://192.168.1.5/hooks, private",
    "https://[fc00::1]/hooks, unique local",
    "https://[fd12:3456:789a::1]/hooks, unique local",
    "https://224.0.0.1/hooks, multicast",
    "https://0.0.0.0/hooks, wildcard"
  })
  void blocksAddressesInDisallowedRanges(final String url, final String expectedReasonSubstring) {
    SsrfCheckResult result = checker.check(url);

    assertThat(result.safe()).isFalse();
    assertThat(result.reason()).contains(expectedReasonSubstring);
  }

  @Test
  void failsSafeForAHostThatDoesNotResolveAtAll() {
    // Fail-safe posture (this class's own Javadoc): an unresolvable host cannot be proven safe.
    SsrfCheckResult result =
        checker.check("https://this-host-does-not-exist.invalid.clavaris-test/hooks");

    assertThat(result.safe()).isFalse();
    assertThat(result.reason()).contains("does not resolve");
  }

  @Test
  void failsSafeForAMalformedUrl() {
    SsrfCheckResult result = checker.check("not a url at all");

    assertThat(result.safe()).isFalse();
    assertThat(result.reason()).contains("malformed");
  }
}
