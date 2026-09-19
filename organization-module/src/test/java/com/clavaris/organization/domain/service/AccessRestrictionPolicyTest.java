package com.clavaris.organization.domain.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.organization.domain.model.AccessRestrictionEntry;
import com.clavaris.organization.domain.model.RestrictionType;
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

  private static AccessRestrictionEntry blocklistEntry(final String identifier) {
    return AccessRestrictionEntry.create(ORGANIZATION_ID, RestrictionType.BLOCKLIST, identifier);
  }

  private static AccessRestrictionEntry allowlistEntry(final String identifier) {
    return AccessRestrictionEntry.create(ORGANIZATION_ID, RestrictionType.ALLOWLIST, identifier);
  }
}
