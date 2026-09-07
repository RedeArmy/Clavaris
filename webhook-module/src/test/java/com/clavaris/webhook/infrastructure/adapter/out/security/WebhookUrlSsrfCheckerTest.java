package com.clavaris.webhook.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

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

  @Test
  void blocksLoopbackAddresses() {
    SsrfCheckResult result = checker.check("https://127.0.0.1/webhooks");

    assertThat(result.safe()).isFalse();
    assertThat(result.reason()).contains("loopback");
  }

  @Test
  void blocksTheIpv6LoopbackAddress() {
    SsrfCheckResult result = checker.check("https://[::1]/webhooks");

    assertThat(result.safe()).isFalse();
    assertThat(result.reason()).contains("loopback");
  }

  @Test
  void blocksTheCloudMetadataAddress() {
    // 169.254.169.254 — the single most concrete real-world consequence of this finding
    // (TD-SEC-053's own register wording): AWS/GCP/Azure all serve instance credentials here.
    SsrfCheckResult result = checker.check("https://169.254.169.254/latest/meta-data/");

    assertThat(result.safe()).isFalse();
    assertThat(result.reason()).contains("link-local");
  }

  @Test
  void blocksRfc1918PrivateAddresses() {
    assertThat(checker.check("https://10.0.0.5/hooks").safe()).isFalse();
    assertThat(checker.check("https://172.16.0.5/hooks").safe()).isFalse();
    assertThat(checker.check("https://192.168.1.5/hooks").safe()).isFalse();
  }

  @Test
  void blocksIpv6UniqueLocalAddresses() {
    // fc00::/7 (RFC 4193) — the modern IPv6 private range isSiteLocalAddress() alone does not
    // recognise (it only knows the deprecated fec0::/10) — this class's own documented gap fix.
    SsrfCheckResult fc00 = checker.check("https://[fc00::1]/hooks");
    SsrfCheckResult fd12 = checker.check("https://[fd12:3456:789a::1]/hooks");

    assertThat(fc00.safe()).isFalse();
    assertThat(fc00.reason()).contains("unique local");
    assertThat(fd12.safe()).isFalse();
  }

  @Test
  void blocksMulticastAddresses() {
    SsrfCheckResult result = checker.check("https://224.0.0.1/hooks");

    assertThat(result.safe()).isFalse();
    assertThat(result.reason()).contains("multicast");
  }

  @Test
  void blocksTheWildcardAddress() {
    SsrfCheckResult result = checker.check("https://0.0.0.0/hooks");

    assertThat(result.safe()).isFalse();
    assertThat(result.reason()).contains("wildcard");
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
