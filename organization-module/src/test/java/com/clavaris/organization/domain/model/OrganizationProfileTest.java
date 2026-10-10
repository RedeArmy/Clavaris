package com.clavaris.organization.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrganizationProfileTest {

  private final UUID organizationId = UUID.randomUUID();

  @Test
  void anOrganizationNobodyHasFilledInHasNothingSet() {
    final OrganizationProfile profile = OrganizationProfile.empty(organizationId);

    assertThat(profile.description()).isEmpty();
    assertThat(profile.applicationName()).isEmpty();
    assertThat(profile.brandColor()).isEmpty();
    assertThat(profile.hasLogo()).isFalse();
    assertThat(profile.logoUpdatedAt()).isEmpty();
  }

  @Test
  void detailsAreTrimmedAndABlankOneIsTheSameAsNone() {
    final OrganizationProfile profile =
        OrganizationProfile.empty(organizationId)
            .withDetails("  Hiring tools for recruiters  ", "   ", "");

    assertThat(profile.description()).contains("Hiring tools for recruiters");
    assertThat(profile.applicationName()).isEmpty();
    assertThat(profile.brandColor()).isEmpty();
  }

  @Test
  void aBrandColourIsKeptInLowerCase() {
    assertThat(
            OrganizationProfile.empty(organizationId)
                .withDetails(null, null, " #2563EB ")
                .brandColor())
        .contains("#2563eb");
  }

  @Test
  void aMalformedBrandColourIsRefused() {
    for (final String bad : new String[] {"2563eb", "#256", "#2563ebff", "blue", "#gggggg"}) {
      assertThatThrownBy(
              () -> OrganizationProfile.empty(organizationId).withDetails(null, null, bad))
          .as(bad)
          .isInstanceOf(IllegalArgumentException.class)
          .hasMessageContaining("hex colour");
    }
  }

  @Test
  void theTextLimitsAreInclusive() {
    OrganizationProfile.empty(organizationId)
        .withDetails(
            "d".repeat(OrganizationProfile.MAX_DESCRIPTION_LENGTH),
            "a".repeat(OrganizationProfile.MAX_APPLICATION_NAME_LENGTH),
            null);

    assertThatThrownBy(
            () ->
                OrganizationProfile.empty(organizationId)
                    .withDetails(
                        "d".repeat(OrganizationProfile.MAX_DESCRIPTION_LENGTH + 1), null, null))
        .hasMessageContaining("description must not exceed 500");
    assertThatThrownBy(
            () ->
                OrganizationProfile.empty(organizationId)
                    .withDetails(
                        null,
                        "a".repeat(OrganizationProfile.MAX_APPLICATION_NAME_LENGTH + 1),
                        null))
        .hasMessageContaining("application name must not exceed 100");
  }

  @Test
  void changingTheDetailsKeepsTheLogo() {
    final Instant at = Instant.parse("2026-10-10T12:00:00Z");
    final OrganizationProfile withLogo =
        OrganizationProfile.empty(organizationId).withLogoUpdatedAt(at);

    final OrganizationProfile changed = withLogo.withDetails("A description", "Acme", "#111111");

    assertThat(changed.logoUpdatedAt()).contains(at);
    assertThat(changed.hasLogo()).isTrue();
  }

  @Test
  void removingTheLogoKeepsTheDetails() {
    final OrganizationProfile profile =
        OrganizationProfile.empty(organizationId)
            .withDetails("A description", "Acme", "#111111")
            .withLogoUpdatedAt(Instant.now())
            .withoutLogo();

    assertThat(profile.hasLogo()).isFalse();
    assertThat(profile.description()).contains("A description");
    assertThat(profile.applicationName()).contains("Acme");
  }
}
