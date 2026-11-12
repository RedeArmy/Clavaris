package com.clavaris.webhook.infrastructure.adapter.out.security;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

/**
 * TD-SEC-097: closes the window between {@link WebhookUrlSsrfChecker}'s lookup and the one the
 * JDK's {@code HttpClient} makes to connect. The client has no way to be handed a resolved address
 * for a plain URL (and an IP in the URL would break TLS name checking), so the resolution itself is
 * made to repeat the answer that was checked: while a delivery to a host is in flight, this
 * resolver returns the addresses {@link PinnedAddresses} holds for it, and everything else (every
 * other host, every reverse lookup) goes to the JDK's own resolver untouched. The name stays in the
 * URL, so SNI, certificate verification and the {@code Host} header are exactly what they were.
 *
 * <p>Registered through {@code META-INF/services}; the JDK allows one provider per JVM, and this is
 * it. A host nobody has pinned costs one map lookup.
 */
public final class PinningInetAddressResolverProvider extends InetAddressResolverProvider {

  // The JDK's ServiceLoader needs a public no-argument constructor; AtLeastOneConstructor demands
  // it be written and UnnecessaryConstructor then flags it, the same pair WebhookUrlSsrfChecker's
  // identical suppression documents.
  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public PinningInetAddressResolverProvider() {
    super();
  }

  @Override
  public InetAddressResolver get(final Configuration configuration) {
    final InetAddressResolver builtin = configuration.builtinResolver();
    return new InetAddressResolver() {
      @Override
      public Stream<InetAddress> lookupByName(final String host, final LookupPolicy policy)
          throws UnknownHostException {
        final Optional<List<InetAddress>> pinned = PinnedAddresses.lookup(host);
        if (pinned.isEmpty()) {
          return builtin.lookupByName(host, policy);
        }
        final List<InetAddress> matching =
            pinned.get().stream().filter(address -> allowedBy(policy, address)).toList();
        if (matching.isEmpty()) {
          throw new UnknownHostException(host);
        }
        return matching.stream();
      }

      @Override
      public String lookupByAddress(final byte[] address) throws UnknownHostException {
        return builtin.lookupByAddress(address);
      }
    };
  }

  @Override
  public String name() {
    return "clavaris-webhook-pinning";
  }

  // The policy says which families the caller wants (for instance IPv4 only on a host without
  // IPv6).
  private static boolean allowedBy(
      final InetAddressResolver.LookupPolicy policy, final InetAddress address) {
    final int wanted = policy.characteristics();
    final boolean ipv4 = (wanted & InetAddressResolver.LookupPolicy.IPV4) != 0;
    final boolean ipv6 = (wanted & InetAddressResolver.LookupPolicy.IPV6) != 0;
    return address.getAddress().length == 4 ? ipv4 : ipv6;
  }
}
