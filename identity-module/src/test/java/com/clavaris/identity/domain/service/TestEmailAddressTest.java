package com.clavaris.identity.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TestEmailAddressTest {

  @Test
  void aRegularAddressIsNeverATestAddress() {
    assertThat(TestEmailAddress.isTestAddress("real-user@example.com")).isFalse();
  }

  @Test
  void anAddressCarryingTheMarkerInItsLocalPartIsATestAddress() {
    assertThat(TestEmailAddress.isTestAddress("real-user+clavaris_test@example.com")).isTrue();
  }

  // The marker only means something in the local part — same Clerk +clerk_test convention this
  // mirrors never treats a domain-side occurrence as significant.
  @Test
  void theMarkerAppearingOnlyInTheDomainDoesNotCount() {
    assertThat(TestEmailAddress.isTestAddress("real-user@clavaris_test.example.com")).isFalse();
  }

  @Test
  void aMalformedAddressWithNoAtSignIsNeverATestAddressUnlessTheWholeStringCarriesTheMarker() {
    assertThat(TestEmailAddress.isTestAddress("not-an-email")).isFalse();
    assertThat(TestEmailAddress.isTestAddress("not-an-email+clavaris_test")).isTrue();
  }
}
