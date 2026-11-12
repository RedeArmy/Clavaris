package com.clavaris.webhook.infrastructure.adapter.out.security;

import java.net.InetAddress;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * TD-SEC-097: the addresses {@link WebhookUrlSsrfChecker} validated for a host, held for as long as
 * a delivery to that host is in flight so that {@link PinningInetAddressResolverProvider} hands the
 * HTTP client exactly those and not a second DNS answer.
 *
 * <p>Static because the resolver provider is created by the JDK's {@code ServiceLoader}, outside
 * any Spring context. Reference-counted per host: two deliveries to one host at once share an
 * entry, and it goes when the last one finishes. Only addresses that passed the SSRF check are ever
 * stored, so whichever of two concurrent pins wins, the connection still goes to a validated
 * address.
 */
public final class PinnedAddresses {

  private static final ConcurrentMap<String, Entry> PINS = new ConcurrentHashMap<>();

  private record Entry(List<InetAddress> addresses, int holders) {}

  private PinnedAddresses() {
    // Static registry only — no instance state.
  }

  /**
   * Pins {@code addresses} for {@code host}; closing the result releases it. With no addresses (a
   * literal IP, which is never resolved, or a caller that checked nothing) there is nothing to pin
   * and the result releases nothing.
   */
  public static PinHandle pin(final String host, final List<InetAddress> addresses) {
    if (addresses.isEmpty()) {
      return new PinHandle(null);
    }
    final String key = normalise(host);
    PINS.merge(
        key,
        new Entry(List.copyOf(addresses), 1),
        (held, added) -> new Entry(added.addresses(), held.holders() + 1));
    return new PinHandle(key);
  }

  /** The pinned addresses for {@code host}, if a delivery to it is in flight. */
  public static Optional<List<InetAddress>> lookup(final String host) {
    return Optional.ofNullable(PINS.get(normalise(host))).map(Entry::addresses);
  }

  /** How many hosts are pinned right now; for tests. */
  /* package */ static int size() {
    return PINS.size();
  }

  private static String normalise(final String host) {
    return host.toLowerCase(Locale.ROOT);
  }

  /** Releases a pin. */
  public static final class PinHandle implements AutoCloseable {
    private final String key;
    private boolean released;

    private PinHandle(final String key) {
      this.key = key;
    }

    @Override
    public void close() {
      if (released || key == null) {
        return;
      }
      released = true;
      PINS.computeIfPresent(
          key,
          (host, held) ->
              held.holders() <= 1 ? null : new Entry(held.addresses(), held.holders() - 1));
    }
  }
}
