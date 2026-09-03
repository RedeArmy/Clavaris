package com.clavaris.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** TD-FUT-026: platform-tier mirror of {@code KnownDeviceTest}. */
class PlatformKnownDeviceTest {

  private final PlatformAccountId platformAccountId = new PlatformAccountId(UUID.randomUUID());

  @Test
  void recognizeCarriesTheGivenFieldsAndStartsWithFirstSeenEqualToLastSeen() {
    PlatformKnownDevice device =
        PlatformKnownDevice.recognize(
            platformAccountId, "Mozilla/5.0 Test Browser", "a-token-hash");

    assertThat(device.platformAccountId()).isEqualTo(platformAccountId);
    assertThat(device.userAgent()).isEqualTo("Mozilla/5.0 Test Browser");
    assertThat(device.deviceTokenHash()).isEqualTo("a-token-hash");
    assertThat(device.firstSeenAt()).isEqualTo(device.lastSeenAt());
  }

  @Test
  void touchAdvancesLastSeenAtWithoutAffectingFirstSeenAt() {
    PlatformKnownDevice device =
        PlatformKnownDevice.recognize(
            platformAccountId, "Mozilla/5.0 Test Browser", "a-token-hash");
    Instant originalFirstSeenAt = device.firstSeenAt();

    device.touch();

    assertThat(device.firstSeenAt()).isEqualTo(originalFirstSeenAt);
    assertThat(device.lastSeenAt()).isAfterOrEqualTo(originalFirstSeenAt);
  }

  @Test
  void reconstituteKeepsTheRealPersistedIdRatherThanMintingANewOne() {
    UUID persistedId = UUID.randomUUID();
    Instant firstSeenAt = Instant.parse("2026-01-01T00:00:00Z");
    Instant lastSeenAt = Instant.parse("2026-01-02T00:00:00Z");

    PlatformKnownDevice device =
        PlatformKnownDevice.reconstitute(
            persistedId, platformAccountId, "Some UA", "a-token-hash", firstSeenAt, lastSeenAt);

    assertThat(device.id()).isEqualTo(persistedId);
    assertThat(device.deviceTokenHash()).isEqualTo("a-token-hash");
    assertThat(device.firstSeenAt()).isEqualTo(firstSeenAt);
    assertThat(device.lastSeenAt()).isEqualTo(lastSeenAt);
  }
}
