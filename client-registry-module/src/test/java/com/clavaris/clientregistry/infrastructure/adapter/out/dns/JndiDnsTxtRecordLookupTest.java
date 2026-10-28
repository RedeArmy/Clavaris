package com.clavaris.clientregistry.infrastructure.adapter.out.dns;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * TD-PERF-008: this class's own first test, ever — real DNS lookups against real, stable public
 * records rather than a mock, consistent with this project's own "confirmed live, not assumed"
 * discipline (the same discipline that found, during this exact fix, that {@code InitialDirContext}
 * does not implement {@link AutoCloseable} despite the technical-debt register row's own text
 * having assumed it did). Requires real outbound DNS resolution — the same dependency every other
 * network-touching part of this project's own CI already has (Maven Central, container registries),
 * not a new category of fragility.
 */
class JndiDnsTxtRecordLookupTest {

  private static final int ATTEMPTS = 3;
  private static final long RETRY_PAUSE_MILLIS = 1000;

  private final JndiDnsTxtRecordLookup lookup = new JndiDnsTxtRecordLookup();

  // google.com's own DMARC TXT record — a long-lived, stable, publicly documented record
  // (RFC 7489) from a domain with every incentive to never let it lapse, chosen specifically so
  // this test doesn't depend on anything this project itself controls or could accidentally break.
  // The lookup answers an empty list for any failure, by design, so a single dropped UDP packet
  // looks exactly like "no such record". This is the one test that talks to the real DNS, so it
  // gets a few attempts before it concludes the record is missing; a real regression (wrong
  // parsing, wrong provider) fails all of them.
  @Test
  void returnsRealTxtValuesForARealStableRecord() throws InterruptedException {
    List<String> values = lookup.lookupTxtRecords("_dmarc.google.com");
    for (int attempt = 1; attempt < ATTEMPTS && values.isEmpty(); attempt++) {
      Thread.sleep(RETRY_PAUSE_MILLIS);
      values = lookup.lookupTxtRecords("_dmarc.google.com");
    }

    assertThat(values).isNotEmpty().anyMatch(value -> value.startsWith("v=DMARC1"));
  }

  // RFC 2606 §2: ".invalid" is reserved specifically for this — guaranteed to never resolve,
  // unlike picking an arbitrary real domain and assuming it stays unregistered forever.
  @Test
  void returnsAnEmptyListRatherThanThrowingForANonExistentDomain() {
    List<String> values =
        lookup.lookupTxtRecords("this-domain-does-not-exist-clavaris-test.invalid");

    assertThat(values).isEmpty();
  }
}
