package com.clavaris.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class SigningKeyTest {

  private final OrganizationId organizationId = new OrganizationId(UUID.randomUUID());

  @Test
  void activateCarriesTheGivenFieldsAndStartsUnretired() {
    SigningKey key = SigningKey.activate(organizationId, "a-kid", "RS256");

    assertThat(key.organizationId()).isEqualTo(organizationId);
    assertThat(key.kid()).isEqualTo("a-kid");
    assertThat(key.algorithm()).isEqualTo("RS256");
    assertThat(key.activeFrom()).isNotNull();
    assertThat(key.retiredAt()).isEmpty();
  }

  @Test
  void retireMarksTheKeyRetiredWithoutErasingItsMetadata() {
    // JWKS keeps serving a retired key until every token signed under it has expired —
    // retiring must never look like the row was deleted.
    SigningKey key = SigningKey.activate(organizationId, "a-kid", "RS256");

    key.retire();

    assertThat(key.retiredAt()).isPresent();
    assertThat(key.kid()).isEqualTo("a-kid");
  }

  @Test
  void purgeImmediatelyRetiresTheKeyToATimestampFarInThePast() {
    // TD-SEC-029: unlike retire() (timestamped "now", giving the normal overlap window), an
    // emergency purge must land outside any real overlap window immediately — asserting a fixed
    // upper bound (not just "before now") is what actually proves that, since "before now" would
    // also be true of a plain retire() called a moment ago.
    SigningKey key = SigningKey.activate(organizationId, "a-kid", "RS256");

    key.purgeImmediately();

    assertThat(key.retiredAt()).contains(Instant.EPOCH);
  }

  @Test
  void purgeImmediatelyOverridesAPriorNormalRetirement() {
    SigningKey key = SigningKey.activate(organizationId, "a-kid", "RS256");
    key.retire();

    key.purgeImmediately();

    assertThat(key.retiredAt()).contains(Instant.EPOCH);
  }

  @Test
  void reconstituteKeepsTheRealPersistedIdRatherThanMintingANewOne() {
    // Same bug class already caught once in JpaAccountRepository's own history.
    UUID persistedId = UUID.randomUUID();
    Instant persistedActiveFrom = Instant.parse("2026-01-01T00:00:00Z");
    Instant persistedRetiredAt = Instant.parse("2026-02-01T00:00:00Z");

    SigningKey key =
        SigningKey.reconstitute(
            persistedId, organizationId, "a-kid", "RS256", persistedActiveFrom, persistedRetiredAt);

    assertThat(key.id()).isEqualTo(persistedId);
    assertThat(key.organizationId()).isEqualTo(organizationId);
    assertThat(key.activeFrom()).isEqualTo(persistedActiveFrom);
    assertThat(key.retiredAt()).contains(persistedRetiredAt);
  }
}
