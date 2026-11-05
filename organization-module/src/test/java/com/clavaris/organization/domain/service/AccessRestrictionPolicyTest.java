package com.clavaris.organization.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.organization.domain.model.AccessRestrictionEntry;
import com.clavaris.organization.domain.model.RestrictionType;
import com.clavaris.organization.domain.service.AccessRestrictionDecision.Reason;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class AccessRestrictionPolicyTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  @Test
  void allowsEverythingWhenNoRestrictionEntriesExist() {
    assertThat(AccessRestrictionPolicy.isAllowed(List.of(), "anyone@example.com")).isTrue();
  }

  @Test
  void blocksAnExactEmailOnTheBlocklist() {
    List<AccessRestrictionEntry> entries = List.of(blocklistEntry("blocked@example.com"));

    assertThat(AccessRestrictionPolicy.isAllowed(entries, "blocked@example.com")).isFalse();
    assertThat(AccessRestrictionPolicy.isAllowed(entries, "someone-else@example.com")).isTrue();
  }

  @Test
  void blocksAWholeDomainOnTheBlocklist() {
    List<AccessRestrictionEntry> entries = List.of(blocklistEntry("@blocked.example.com"));

    assertThat(AccessRestrictionPolicy.isAllowed(entries, "anyone@blocked.example.com")).isFalse();
    assertThat(AccessRestrictionPolicy.isAllowed(entries, "anyone@allowed.example.com")).isTrue();
  }

  @Test
  void allowlistWinsOutrightOnceAnyAllowlistEntryExists() {
    List<AccessRestrictionEntry> entries =
        List.of(
            allowlistEntry("@allowed.example.com"), blocklistEntry("blocked@allowed.example.com"));

    // Matches the allowlist domain, even though it also matches a blocklist entry — Clerk's own
    // documented "blocklist ignored once allowlist is enabled" behavior.
    assertThat(AccessRestrictionPolicy.isAllowed(entries, "blocked@allowed.example.com")).isTrue();
    assertThat(AccessRestrictionPolicy.isAllowed(entries, "anyone@allowed.example.com")).isTrue();
    assertThat(AccessRestrictionPolicy.isAllowed(entries, "anyone@somewhere-else.com")).isFalse();
  }

  @Test
  void matchingIsCaseInsensitive() {
    List<AccessRestrictionEntry> entries = List.of(blocklistEntry("Blocked@Example.com"));

    assertThat(AccessRestrictionPolicy.isAllowed(entries, "blocked@example.com")).isFalse();
  }

  // TD-FUT-069: the verdict and the rule behind it, one case per reason.
  @Test
  void evaluateNamesTheRuleThatDecided() {
    assertThat(AccessRestrictionPolicy.evaluate(List.of(), "a@x.test"))
        .isEqualTo(new AccessRestrictionDecision(true, Reason.NOT_RESTRICTED, null));

    final List<AccessRestrictionEntry> blocklist = List.of(blocklistEntry("@spam.test"));
    assertThat(AccessRestrictionPolicy.evaluate(blocklist, "Bob@Spam.test"))
        .isEqualTo(new AccessRestrictionDecision(false, Reason.BLOCKED_BY_BLOCKLIST, "@spam.test"));
    assertThat(AccessRestrictionPolicy.evaluate(blocklist, "ana@x.test"))
        .isEqualTo(new AccessRestrictionDecision(true, Reason.NOT_ON_BLOCKLIST, null));

    final List<AccessRestrictionEntry> allowlist =
        List.of(allowlistEntry("@ok.test"), blocklistEntry("bad@ok.test"));
    assertThat(AccessRestrictionPolicy.evaluate(allowlist, "ana@ok.test"))
        .isEqualTo(new AccessRestrictionDecision(true, Reason.ALLOWED_BY_ALLOWLIST, "@ok.test"));
    assertThat(AccessRestrictionPolicy.evaluate(allowlist, "ana@other.test"))
        .isEqualTo(new AccessRestrictionDecision(false, Reason.NOT_ON_ALLOWLIST, null));
  }

  // The preview must never disagree with the gate it previews.
  @Test
  void isAllowedIsAlwaysTheVerdictOfEvaluate() {
    final List<AccessRestrictionEntry> entries =
        List.of(allowlistEntry("@ok.test"), blocklistEntry("bad@ok.test"));
    for (final String email : new String[] {"a@ok.test", "bad@ok.test", "z@else.test"}) {
      assertThat(AccessRestrictionPolicy.isAllowed(entries, email))
          .as(email)
          .isEqualTo(AccessRestrictionPolicy.evaluate(entries, email).allowed());
    }
  }

  private static AccessRestrictionEntry blocklistEntry(final String identifier) {
    return AccessRestrictionEntry.create(ORGANIZATION_ID, RestrictionType.BLOCKLIST, identifier);
  }

  private static AccessRestrictionEntry allowlistEntry(final String identifier) {
    return AccessRestrictionEntry.create(ORGANIZATION_ID, RestrictionType.ALLOWLIST, identifier);
  }
}
