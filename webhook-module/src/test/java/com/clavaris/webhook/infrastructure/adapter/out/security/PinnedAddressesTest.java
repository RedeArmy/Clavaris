package com.clavaris.webhook.infrastructure.adapter.out.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.jupiter.api.Test;

class PinnedAddressesTest {

  private static InetAddress address(final String literal) throws UnknownHostException {
    return InetAddress.getByName(literal);
  }

  @Test
  void holdsTheAddressesOnlyWhileAPinIsOpenAndIgnoresCase() throws Exception {
    try (PinnedAddresses.PinHandle ignored =
        PinnedAddresses.pin("Hook.Example.Test", List.of(address("203.0.113.7")))) {
      assertThat(PinnedAddresses.lookup("hook.example.test"))
          .contains(List.of(address("203.0.113.7")));
    }

    assertThat(PinnedAddresses.lookup("hook.example.test")).isEmpty();
  }

  @Test
  void aSecondDeliveryToTheSameHostKeepsThePinUntilTheLastOneEnds() throws Exception {
    final PinnedAddresses.PinHandle first =
        PinnedAddresses.pin("shared.example.test", List.of(address("203.0.113.7")));
    final PinnedAddresses.PinHandle second =
        PinnedAddresses.pin("shared.example.test", List.of(address("203.0.113.8")));

    first.close();
    assertThat(PinnedAddresses.lookup("shared.example.test")).isPresent();
    second.close();
    second.close(); // closing twice must not release somebody else's hold
    assertThat(PinnedAddresses.lookup("shared.example.test")).isEmpty();
  }

  @Test
  void pinningNothingLeavesNothingBehind() {
    final int before = PinnedAddresses.size();

    PinnedAddresses.pin("literal.example.test", List.of()).close();

    assertThat(PinnedAddresses.lookup("literal.example.test")).isEmpty();
    assertThat(PinnedAddresses.size()).isEqualTo(before);
  }
}
