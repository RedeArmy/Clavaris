package com.clavaris.webhook.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TD-SEC-097, through the JDK itself: the provider is registered in {@code META-INF/services}, so
 * {@link InetAddress} lookups in this JVM go through it exactly as the HTTP client's do. A host
 * name per test, because the JVM caches what it resolves.
 */
class PinningInetAddressResolverProviderTest {

  @Test
  void aPinnedHostResolvesToTheCheckedAddressEvenThoughTheNameResolvesNowhere() throws Exception {
    // ".invalid" is reserved and never resolves (RFC 6761), so this can only come from the pin.
    final InetAddress checked = InetAddress.getByName("203.0.113.7");

    try (PinnedAddresses.PinHandle _ =
        PinnedAddresses.pin("pinned-one.example.invalid", List.of(checked))) {
      assertThat(InetAddress.getAllByName("pinned-one.example.invalid"))
          .extracting(InetAddress::getHostAddress)
          .containsExactly("203.0.113.7");
    }
  }

  @Test
  void anUnpinnedNameStillResolvesThroughTheNormalResolver() throws Exception {
    assertThat(InetAddress.getByName("localhost").isLoopbackAddress()).isTrue();
    assertThatExceptionOfType(UnknownHostException.class)
        .isThrownBy(() -> InetAddress.getByName("never-pinned.example.invalid"));
  }
}
