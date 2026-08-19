package com.clavaris.organization.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrganizationTest {

  @Test
  void registerCarriesTheGivenName() {
    final Organization organization = Organization.register("JobSeeker");

    assertThat(organization.name()).isEqualTo("JobSeeker");
    assertThat(organization.id()).isNotNull();
    assertThat(organization.createdAt()).isNotNull();
  }

  @Test
  void rejectsABlankName() {
    assertThatIllegalArgumentException().isThrownBy(() -> Organization.register(" "));
  }

  @Test
  void rejectsANullName() {
    assertThatIllegalArgumentException().isThrownBy(() -> Organization.register(null));
  }

  @Test
  void reconstituteKeepsTheRealPersistedIdRatherThanMintingANewOne() {
    // Same bug class already caught once in JpaAccountRepository's own history (and again in
    // PlatformClient's) — reconstitute must return the exact id passed in, not a fresh
    // UUID.randomUUID(), or a repository reading a row back would silently lose its identity.
    final UUID persistedId = UUID.randomUUID();
    final Instant persistedCreatedAt = Instant.parse("2026-01-01T00:00:00Z");

    final Organization organization =
        Organization.reconstitute(persistedId, "JobSeeker", persistedCreatedAt);

    assertThat(organization.id()).isEqualTo(persistedId);
    assertThat(organization.name()).isEqualTo("JobSeeker");
    assertThat(organization.createdAt()).isEqualTo(persistedCreatedAt);
  }
}
