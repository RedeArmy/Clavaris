package com.clavaris.webhook.infrastructure.adapter.out.security;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * TD-SEC-053: real SSRF protection for webhook endpoint URLs. {@code
 * WebhookEndpoint.requireValidUrl} (domain layer) only ever checks the URL's scheme (BR-WEBHOOK-07,
 * https-only) — it can never check whether the host actually resolves to Clavaris's own internal
 * network, a cloud-metadata endpoint ({@code 169.254.169.254}, shared by AWS/GCP/Azure), or any
 * other private/loopback/link-local range, because that requires real DNS resolution, an I/O
 * operation the hexagonal dependency rule (domain/ depends on nothing) forbids from living in
 * {@code domain/model} at all. This class is the actual guard, shared by both call sites that ever
 * act on an operator-supplied URL: {@code RegisterWebhookEndpointService} (via the {@code
 * WebhookUrlSsrfGuard} port) at registration time, and {@code JdkHttpWebhookSender} directly (same
 * module, no port needed for an infra-to-infra call) at delivery time, immediately before every
 * real connection attempt — not just once at registration.
 *
 * <p><b>Why both call sites, not just one:</b> DNS is not a fixed mapping. A URL that resolved to a
 * public address at registration time can be re-pointed at an internal address by the time it is
 * actually delivered to (DNS rebinding) — checking only at registration would leave that window
 * open for the entire remaining lifetime of the endpoint. Checking again immediately before every
 * delivery closes that, at the cost of one extra DNS lookup per attempt.
 *
 * <p><b>The window between this check and the connection (TD-SEC-097):</b> the JDK's {@link
 * java.net.http.HttpClient} resolves the name again to connect, and has no API to take an address
 * that was already resolved. The result of this check therefore carries the addresses it validated,
 * and {@code JdkHttpWebhookSender} pins them in {@link PinnedAddresses} for the length of the
 * delivery, where {@link PinningInetAddressResolverProvider} gives the client exactly those. A
 * hostile DNS server can no longer answer differently the second time.
 *
 * <p>Resolves every address a host maps to via {@link InetAddress#getAllByName}, not just the first
 * answer — a multi-record DNS response where only a later address is private would otherwise slip
 * through undetected. {@link InetAddress#isSiteLocalAddress()} covers IPv4 private ranges (RFC
 * 1918) and only the long-deprecated IPv6 site-local range ({@code fec0::/10}), not the modern IPv6
 * ULA range that actually replaced it ({@code fc00::/7}, RFC 4193) — {@link
 * #isIpv6UniqueLocalAddress(InetAddress)} is the manual check that specific gap needs.
 */
@Component
public class WebhookUrlSsrfChecker {

  // AtLeastOneConstructor demands this exist; UnnecessaryConstructor then flags it as equivalent
  // to the implicit default — an unavoidable pair for a stateless class with a public no-arg
  // constructor, same tension WebhookUseCaseConfig's own identical suppression already documents.
  // Public, not package-private: JdkHttpWebhookSenderTest (a different package, out.http)
  // constructs this directly to exercise the real checker rather than its own ALLOW_ALL stub.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public WebhookUrlSsrfChecker() {
    // Intentionally empty — this class holds no state, only the check() method below.
  }

  // Three exits (malformed/unresolvable host, a disallowed address found, everything clear) is
  // clearer here than forcing a single-return shape onto three genuinely different outcomes —
  // same rationale RegisterOAuthClientController's own identical suppression documents.
  public SsrfCheckResult check(final String url) {
    final String host = extractHost(url);
    if (host == null) {
      return SsrfCheckResult.unsafe("URL is malformed or has no resolvable host");
    }

    final InetAddress[] addresses;
    try {
      addresses = InetAddress.getAllByName(host);
    } catch (final UnknownHostException _) {
      // Fail-safe: an unresolvable host cannot be proven safe, so it is treated as unsafe rather
      // than let through — the posture a security boundary must take when it cannot verify a
      // claim, not an incidental side effect of the exception type.
      return SsrfCheckResult.unsafe("host does not resolve: " + host);
    }

    for (final InetAddress address : addresses) {
      final String reason = disallowedReason(address);
      if (reason != null) {
        return SsrfCheckResult.unsafe(
            "host '" + host + "' resolves to " + address.getHostAddress() + " (" + reason + ")");
      }
    }
    return SsrfCheckResult.safeFor(List.of(addresses));
  }

  private String extractHost(final String url) {
    try {
      return URI.create(url).getHost();
    } catch (final IllegalArgumentException _) {
      return null;
    }
  }

  private String disallowedReason(final InetAddress address) {
    if (address.isLoopbackAddress()) {
      return "loopback";
    }
    if (address.isLinkLocalAddress()) {
      // Covers 169.254.0.0/16 (including the cloud-metadata endpoint 169.254.169.254) and the
      // IPv6 link-local equivalent (fe80::/10) automatically.
      return "link-local";
    }
    if (address.isSiteLocalAddress()) {
      return "private (RFC 1918 / deprecated IPv6 site-local)";
    }
    if (isIpv6UniqueLocalAddress(address)) {
      return "private (IPv6 unique local, RFC 4193)";
    }
    if (address.isMulticastAddress()) {
      return "multicast";
    }
    if (address.isAnyLocalAddress()) {
      return "wildcard/unspecified";
    }
    return null;
  }

  // isSiteLocalAddress() only recognises the deprecated fec0::/10 IPv6 range, not the fc00::/7
  // range that actually replaced it (RFC 4193) — masking the address's first byte with 0xFE and
  // comparing to 0xFC is the standard way to test a 7-bit prefix against the top 7 bits of a byte,
  // since fc00::/7 covers both fc00:: and fd00:: (bytes 0xFC and 0xFD).
  private boolean isIpv6UniqueLocalAddress(final InetAddress address) {
    final byte[] bytes = address.getAddress();
    return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
  }
}
