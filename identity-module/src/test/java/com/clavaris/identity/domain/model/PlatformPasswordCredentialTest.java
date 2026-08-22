package com.clavaris.identity.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Mirrors {@link PasswordCredentialTest} — same BR-ID-01 discipline, platform tier. */
class PlatformPasswordCredentialTest {

  private final PlatformAccountId platformAccountId = PlatformAccountId.newId();

  @Test
  void issueCarriesTheGivenHashAndPlatformAccountId() {
    PlatformPasswordCredential credential =
        PlatformPasswordCredential.issue(platformAccountId, "argon2id$...");

    assertThat(credential.platformAccountId()).isEqualTo(platformAccountId);
    assertThat(credential.passwordHash()).isEqualTo("argon2id$...");
    assertThat(credential.updatedAt()).isNotNull();
  }

  @Test
  void rejectsABlankHash() {
    // A hasher bug producing an empty hash must fail loudly here, not silently reach persistence
    // as a platform account nothing (and everything) authenticates against.
    assertThatIllegalArgumentException()
        .isThrownBy(() -> PlatformPasswordCredential.issue(platformAccountId, "  "));
  }

  @Test
  void reconstitutePreservesTheRealPersistedIdAndUpdatedAt() {
    UUID id = UUID.randomUUID();
    Instant updatedAt = Instant.parse("2026-08-01T00:00:00Z");

    PlatformPasswordCredential credential =
        PlatformPasswordCredential.reconstitute(
            id, platformAccountId, "argon2id$stored-hash", updatedAt);

    assertThat(credential.id()).isEqualTo(id);
    assertThat(credential.platformAccountId()).isEqualTo(platformAccountId);
    assertThat(credential.passwordHash()).isEqualTo("argon2id$stored-hash");
    assertThat(credential.updatedAt()).isEqualTo(updatedAt);
  }
}
