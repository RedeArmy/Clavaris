package com.clavaris.organization.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrganizationTest {

  private final UUID ownerPlatformAccountId = UUID.randomUUID();

  @Test
  void registerCarriesTheGivenNameAndOwner() {
    final Organization organization = Organization.register("JobSeeker", ownerPlatformAccountId);

    assertThat(organization.name()).isEqualTo("JobSeeker");
    assertThat(organization.id()).isNotNull();
    assertThat(organization.createdAt()).isNotNull();
    assertThat(organization.ownerPlatformAccountId()).isEqualTo(ownerPlatformAccountId);
  }

  @Test
  void rejectsABlankName() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Organization.register(" ", ownerPlatformAccountId));
  }

  @Test
  void rejectsANullName() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> Organization.register(null, ownerPlatformAccountId));
  }

  // Security finding (SDE-III review, 2026-08-22), regression test for its fix: matches the
  // organizations.name column (varchar(255)) — before this, an over-length name reached the DB
  // unchecked and surfaced as a raw DataIntegrityViolationException instead of a clean rejection.
  @Test
  void rejectsANameLongerThan255Characters() {
    final String tooLong = "a".repeat(256);

    assertThatIllegalArgumentException()
        .isThrownBy(() -> Organization.register(tooLong, ownerPlatformAccountId));
  }

  @Test
  void acceptsANameExactly255CharactersLong() {
    final String exactly255 = "a".repeat(255);

    final Organization organization = Organization.register(exactly255, ownerPlatformAccountId);

    assertThat(organization.name()).isEqualTo(exactly255);
  }

  @Test
  void reconstituteKeepsTheRealPersistedIdRatherThanMintingANewOne() {
    // Same bug class already caught once in JpaAccountRepository's own history (and again in
    // PlatformClient's) — reconstitute must return the exact id passed in, not a fresh
    // UUID.randomUUID(), or a repository reading a row back would silently lose its identity.
    final UUID persistedId = UUID.randomUUID();
    final Instant persistedCreatedAt = Instant.parse("2026-01-01T00:00:00Z");

    final Organization organization =
        Organization.reconstitute(
            persistedId, "JobSeeker", persistedCreatedAt, ownerPlatformAccountId);

    assertThat(organization.id()).isEqualTo(persistedId);
    assertThat(organization.name()).isEqualTo("JobSeeker");
    assertThat(organization.createdAt()).isEqualTo(persistedCreatedAt);
    assertThat(organization.ownerPlatformAccountId()).isEqualTo(ownerPlatformAccountId);
  }
}
