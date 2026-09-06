package com.clavaris.clientregistry.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ClientBrandingTest {

  private final UUID oauthClientId = UUID.randomUUID();

  @Test
  void defineCarriesTheGivenClientAndBranding() {
    ClientBranding branding =
        ClientBranding.define(
            oauthClientId, "https://cdn.example.com/logo.png", "#336699", "JobSeeker");

    assertThat(branding.oauthClientId()).isEqualTo(oauthClientId);
    assertThat(branding.logoUrl()).contains("https://cdn.example.com/logo.png");
    assertThat(branding.primaryColor()).contains("#336699");
    assertThat(branding.applicationDisplayName()).contains("JobSeeker");
    assertThat(branding.id()).isNotNull();
    assertThat(branding.updatedAt()).isEqualTo(branding.createdAt());
  }

  @Test
  void unconfiguredHasEveryFieldAbsent() {
    ClientBranding branding = ClientBranding.unconfigured(oauthClientId);

    assertThat(branding.logoUrl()).isEmpty();
    assertThat(branding.primaryColor()).isEmpty();
    assertThat(branding.applicationDisplayName()).isEmpty();
  }

  @Test
  void rejectsAMalformedLogoUrl() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ClientBranding.define(oauthClientId, "not a url", null, null));
  }

  @Test
  void rejectsARelativeLogoUrl() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ClientBranding.define(oauthClientId, "/logo.png", null, null));
  }

  @Test
  void rejectsAnInsecureHttpLogoUrl() {
    assertThatIllegalArgumentException()
        .isThrownBy(
            () ->
                ClientBranding.define(
                    oauthClientId, "http://cdn.example.com/logo.png", null, null));
  }

  @Test
  void rejectsAPrimaryColorThatIsNotAHexTriplet() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ClientBranding.define(oauthClientId, null, "blue", null));
  }

  @Test
  void acceptsAThreeDigitHexColor() {
    ClientBranding branding = ClientBranding.define(oauthClientId, null, "#369", null);

    assertThat(branding.primaryColor()).contains("#369");
  }

  @Test
  void rejectsABlankDisplayName() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ClientBranding.define(oauthClientId, null, null, "   "));
  }

  @Test
  void rejectsADisplayNameOverTheMaxLength() {
    assertThatIllegalArgumentException()
        .isThrownBy(() -> ClientBranding.define(oauthClientId, null, null, "a".repeat(101)));
  }

  // java:S2925: ClientBranding has no injectable Clock, same Instant.now()-direct convention as
  // every other domain entity in this codebase — see RedirectPolicyTest's own identical
  // suppression rationale.
  @SuppressWarnings("java:S2925")
  @Test
  void withBrandingKeepsTheSameIdAndCreatedAtButStampsAFreshUpdatedAt()
      throws InterruptedException {
    ClientBranding original = ClientBranding.define(oauthClientId, null, "#111111", null);
    Thread.sleep(5);

    ClientBranding updated = original.withBranding(null, "#222222", null);

    assertThat(updated.id()).isEqualTo(original.id());
    assertThat(updated.oauthClientId()).isEqualTo(original.oauthClientId());
    assertThat(updated.createdAt()).isEqualTo(original.createdAt());
    assertThat(updated.primaryColor()).contains("#222222");
    assertThat(updated.updatedAt())
        .as("re-tuning existing branding must stamp a real, later updatedAt")
        .isAfter(original.updatedAt());
  }

  @Test
  void reconstituteKeepsTheRealPersistedIdRatherThanMintingANewOne() {
    UUID persistedId = UUID.randomUUID();
    Instant persistedCreatedAt = Instant.parse("2026-01-01T00:00:00Z");
    Instant persistedUpdatedAt = Instant.parse("2026-01-02T00:00:00Z");

    ClientBranding branding =
        ClientBranding.reconstitute(
            persistedId,
            oauthClientId,
            "https://cdn.example.com/logo.png",
            null,
            null,
            persistedCreatedAt,
            persistedUpdatedAt);

    assertThat(branding.id()).isEqualTo(persistedId);
    assertThat(branding.oauthClientId()).isEqualTo(oauthClientId);
    assertThat(branding.createdAt()).isEqualTo(persistedCreatedAt);
    assertThat(branding.updatedAt()).isEqualTo(persistedUpdatedAt);
  }
}
