package com.clavaris.identity.infrastructure.adapter.out.breachcheck;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import org.junit.jupiter.api.Test;

class PwnedPasswordsBreachedPasswordCheckerTest {

  // "password" -> SHA-1 5BAA61E4C9B93F3F0682250B6CF8331B7EE68FD8 — confirmed via `sha1sum`, a real,
  // checkable test vector rather than an arbitrary string (not copied from memory — a first draft
  // of this constant was off by one trailing character, caught exactly by re-deriving it this way).
  private static final String KNOWN_PASSWORD = "password";
  private static final String KNOWN_PASSWORD_PREFIX = "5BAA6";
  private static final String KNOWN_PASSWORD_SUFFIX = "1E4C9B93F3F0682250B6CF8331B7EE68FD8";

  @Test
  void aMatchingSuffixInTheRangeResponseIsReportedAsBreached() {
    PwnedPasswordsHttpClient httpClient = mock(PwnedPasswordsHttpClient.class);
    when(httpClient.lookupRange(eq(KNOWN_PASSWORD_PREFIX)))
        .thenReturn(
            List.of("0000000000000000000000000000000000:1", KNOWN_PASSWORD_SUFFIX + ":3730330"));
    PwnedPasswordsBreachedPasswordChecker checker =
        new PwnedPasswordsBreachedPasswordChecker(httpClient);

    assertThat(checker.isBreached(KNOWN_PASSWORD)).isTrue();
  }

  @Test
  void matchingIsCaseInsensitiveOnTheReturnedSuffix() {
    PwnedPasswordsHttpClient httpClient = mock(PwnedPasswordsHttpClient.class);
    when(httpClient.lookupRange(eq(KNOWN_PASSWORD_PREFIX)))
        .thenReturn(List.of(KNOWN_PASSWORD_SUFFIX.toLowerCase(java.util.Locale.ROOT) + ":1"));
    PwnedPasswordsBreachedPasswordChecker checker =
        new PwnedPasswordsBreachedPasswordChecker(httpClient);

    assertThat(checker.isBreached(KNOWN_PASSWORD)).isTrue();
  }

  @Test
  void noMatchingSuffixInTheRangeResponseIsReportedAsNotBreached() {
    PwnedPasswordsHttpClient httpClient = mock(PwnedPasswordsHttpClient.class);
    when(httpClient.lookupRange(eq(KNOWN_PASSWORD_PREFIX)))
        .thenReturn(List.of("FFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFFF:1"));
    PwnedPasswordsBreachedPasswordChecker checker =
        new PwnedPasswordsBreachedPasswordChecker(httpClient);

    assertThat(checker.isBreached(KNOWN_PASSWORD)).isFalse();
  }

  @Test
  void anEmptyRangeResponseIsReportedAsNotBreached() {
    PwnedPasswordsHttpClient httpClient = mock(PwnedPasswordsHttpClient.class);
    when(httpClient.lookupRange(eq(KNOWN_PASSWORD_PREFIX))).thenReturn(List.of());
    PwnedPasswordsBreachedPasswordChecker checker =
        new PwnedPasswordsBreachedPasswordChecker(httpClient);

    assertThat(checker.isBreached(KNOWN_PASSWORD)).isFalse();
  }

  // The one test that matters most here — BreachedPasswordChecker's own fail-open contract
  // (BR-ID-07,
  // confirmed with the user): a lookup failure must never propagate, and must never be reported as
  // breached.
  @Test
  void aLookupFailureNeverPropagatesAndIsTreatedAsNotBreached() {
    PwnedPasswordsHttpClient httpClient = mock(PwnedPasswordsHttpClient.class);
    when(httpClient.lookupRange(eq(KNOWN_PASSWORD_PREFIX)))
        .thenThrow(new PwnedPasswordsLookupException("simulated outage"));
    PwnedPasswordsBreachedPasswordChecker checker =
        new PwnedPasswordsBreachedPasswordChecker(httpClient);

    assertThat(checker.isBreached(KNOWN_PASSWORD)).isFalse();
  }

  // A line whose suffix is a real prefix of the computed suffix, but shorter (no ':' boundary at
  // exactly the right position), must never be treated as a match — same discipline as any other
  // "don't let a substring match stand in for an exact match" security check.
  @Test
  void aSuffixThatIsOnlyAPartialPrefixMatchIsNotReportedAsBreached() {
    PwnedPasswordsHttpClient httpClient = mock(PwnedPasswordsHttpClient.class);
    when(httpClient.lookupRange(eq(KNOWN_PASSWORD_PREFIX)))
        .thenReturn(List.of(KNOWN_PASSWORD_SUFFIX.substring(0, 10) + "EXTRA:1"));
    PwnedPasswordsBreachedPasswordChecker checker =
        new PwnedPasswordsBreachedPasswordChecker(httpClient);

    assertThat(checker.isBreached(KNOWN_PASSWORD)).isFalse();
  }
}
