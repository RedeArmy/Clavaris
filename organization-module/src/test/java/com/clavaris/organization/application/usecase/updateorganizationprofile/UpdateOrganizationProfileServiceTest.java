package com.clavaris.organization.application.usecase.updateorganizationprofile;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.OrganizationLogo;
import com.clavaris.organization.domain.model.OrganizationProfile;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class UpdateOrganizationProfileServiceTest {

  private static final byte[] PNG = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1A, '\n', 1, 2, 3};

  private final UUID owner = UUID.randomUUID();
  private final Organization organization = Organization.register("Acme Hiring", owner);
  private final UUID id = organization.id();

  private final OrganizationRepository organizations = mock(OrganizationRepository.class);
  private final OrganizationProfileRepository profiles = mock(OrganizationProfileRepository.class);
  private final OrganizationLogoRepository logos = mock(OrganizationLogoRepository.class);
  private final AuditEventRecorder audit = mock(AuditEventRecorder.class);
  private final UpdateOrganizationProfileService service =
      new UpdateOrganizationProfileService(organizations, profiles, logos, audit);

  private UpdateOrganizationProfileCommand command(
      final String name,
      final String description,
      final String applicationName,
      final String color,
      final OrganizationLogo logo,
      final boolean removeLogo) {
    return new UpdateOrganizationProfileCommand(
        id,
        owner,
        name,
        description,
        applicationName,
        color,
        logo,
        removeLogo,
        AuditActor.platformAccount(owner));
  }

  private void organizationExistsWithNoProfile() {
    when(organizations.findById(id)).thenReturn(Optional.of(organization));
    when(profiles.findByOrganizationId(id)).thenReturn(Optional.empty());
  }

  @Test
  void savesTheNewNameAndTheProfileAndAuditsWhichFieldsChanged() {
    organizationExistsWithNoProfile();

    final UpdateOrganizationProfileResult result =
        service.handle(
            command("  Acme Careers ", "Hiring tools", "Acme Jobs", "#2563EB", null, false));

    final ArgumentCaptor<Organization> saved = ArgumentCaptor.forClass(Organization.class);
    verify(organizations).save(saved.capture());
    assertThat(saved.getValue().name()).isEqualTo("Acme Careers");
    assertThat(saved.getValue().ownerPlatformAccountId()).isEqualTo(owner);

    final ArgumentCaptor<OrganizationProfile> profile =
        ArgumentCaptor.forClass(OrganizationProfile.class);
    verify(profiles).save(profile.capture());
    assertThat(profile.getValue().description()).contains("Hiring tools");
    assertThat(profile.getValue().applicationName()).contains("Acme Jobs");
    assertThat(profile.getValue().brandColor()).contains("#2563eb");

    verify(audit)
        .write(
            any(),
            eq("organization.profile_updated"),
            eq("Organization"),
            eq(id.toString()),
            eq("changed=name,description,applicationName,brandColor"));
    assertThat(result.organization().name()).isEqualTo("Acme Careers");
  }

  // Which fields changed, never what they were changed to: a description is free text.
  @Test
  void theAuditEntryNeverCarriesTheContentOfTheDescription() {
    organizationExistsWithNoProfile();

    service.handle(command("Acme Hiring", "A very private description", null, null, null, false));

    final ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
    verify(audit).write(any(), anyString(), anyString(), anyString(), detail.capture());
    assertThat(detail.getValue()).isEqualTo("changed=description").doesNotContain("private");
  }

  @Test
  void nothingIsWrittenOrAuditedWhenNothingChanged() {
    organizationExistsWithNoProfile();

    final UpdateOrganizationProfileResult result =
        service.handle(command("Acme Hiring", "", "", "", null, false));

    verify(organizations, never()).save(any());
    verify(profiles, never()).save(any());
    verifyNoInteractions(logos, audit);
    assertThat(result.profile().description()).isEmpty();
  }

  @Test
  void theNameIsNotRewrittenWhenOnlyTheProfileChanges() {
    organizationExistsWithNoProfile();

    service.handle(command("Acme Hiring", "A description", null, null, null, false));

    verify(organizations, never()).save(any());
    verify(profiles).save(any());
  }

  @Test
  void aNewLogoIsStoredAndTheProfileRemembersWhen() {
    organizationExistsWithNoProfile();
    final OrganizationLogo logo = OrganizationLogo.of(id, "image/png", PNG);

    final UpdateOrganizationProfileResult result =
        service.handle(command("Acme Hiring", null, null, null, logo, false));

    verify(logos).save(logo);
    assertThat(result.profile().hasLogo()).isTrue();
    verify(audit).write(any(), anyString(), anyString(), anyString(), eq("changed=logo"));
  }

  @Test
  void aNewLogoWinsOverRemoveLogo() {
    organizationExistsWithNoProfile();
    final OrganizationLogo logo = OrganizationLogo.of(id, "image/png", PNG);

    service.handle(command("Acme Hiring", null, null, null, logo, true));

    verify(logos).save(logo);
    verify(logos, never()).deleteByOrganizationId(any());
  }

  @Test
  void removingTheLogoDeletesItAndClearsTheProfile() {
    when(organizations.findById(id)).thenReturn(Optional.of(organization));
    when(profiles.findByOrganizationId(id))
        .thenReturn(Optional.of(OrganizationProfile.empty(id).withLogoUpdatedAt(Instant.now())));

    final UpdateOrganizationProfileResult result =
        service.handle(command("Acme Hiring", null, null, null, null, true));

    verify(logos).deleteByOrganizationId(id);
    assertThat(result.profile().hasLogo()).isFalse();
    verify(audit).write(any(), anyString(), anyString(), anyString(), eq("changed=logo removed"));
  }

  @Test
  void removingALogoThatIsNotThereDoesNothing() {
    organizationExistsWithNoProfile();

    service.handle(command("Acme Hiring", null, null, null, null, true));

    verify(logos, never()).deleteByOrganizationId(any());
    verifyNoInteractions(audit);
  }

  @Test
  void leavingTheLogoAloneKeepsIt() {
    when(organizations.findById(id)).thenReturn(Optional.of(organization));
    final Instant at = Instant.parse("2026-10-10T12:00:00Z");
    when(profiles.findByOrganizationId(id))
        .thenReturn(Optional.of(OrganizationProfile.empty(id).withLogoUpdatedAt(at)));

    final UpdateOrganizationProfileResult result =
        service.handle(command("Acme Hiring", "A description", null, null, null, false));

    assertThat(result.profile().logoUpdatedAt()).contains(at);
    verify(logos, never()).save(any());
    verify(logos, never()).deleteByOrganizationId(any());
  }

  // An Organization that is someone else's is "not found", the same as one that does not exist.
  @Test
  void anOrganizationOfSomeoneElseIsNotFound() {
    when(organizations.findById(id)).thenReturn(Optional.of(organization));
    final UpdateOrganizationProfileCommand foreign =
        new UpdateOrganizationProfileCommand(
            id,
            UUID.randomUUID(),
            "Hijacked",
            null,
            null,
            null,
            null,
            false,
            AuditActor.platformAccount(UUID.randomUUID()));

    assertThatThrownBy(() -> service.handle(foreign))
        .isInstanceOf(OrganizationNotFoundException.class);

    verify(organizations, never()).save(any());
    verifyNoInteractions(profiles, logos, audit);
  }

  @Test
  void anOrganizationThatDoesNotExistIsNotFound() {
    when(organizations.findById(id)).thenReturn(Optional.empty());

    assertThatThrownBy(() -> service.handle(command("Acme", null, null, null, null, false)))
        .isInstanceOf(OrganizationNotFoundException.class);

    verifyNoInteractions(profiles, logos, audit);
  }

  @Test
  void aBlankNameIsRefusedBeforeAnythingIsWritten() {
    organizationExistsWithNoProfile();

    assertThatThrownBy(
            () -> service.handle(command("   ", "A description", null, null, null, false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("blank");
    assertThatThrownBy(() -> service.handle(command(null, null, null, null, null, false)))
        .isInstanceOf(IllegalArgumentException.class);

    verify(organizations, never()).save(any());
    verify(profiles, never()).save(any());
    verifyNoInteractions(logos, audit);
  }

  @Test
  void aMalformedColourIsRefusedBeforeAnythingIsWritten() {
    organizationExistsWithNoProfile();

    assertThatThrownBy(
            () -> service.handle(command("Acme Hiring", null, null, "blue", null, false)))
        .isInstanceOf(IllegalArgumentException.class);

    verify(profiles, never()).save(any());
    verifyNoInteractions(logos, audit);
  }

  // Passing a null actor through is the audit recorder's own concern; the service always has one.
  @Test
  void theAuditEntryIsAttributedToTheActor() {
    organizationExistsWithNoProfile();
    final AuditActor actor = AuditActor.platformAccount(owner);

    service.handle(
        new UpdateOrganizationProfileCommand(
            id, owner, "Renamed", null, null, null, null, false, actor));

    verify(audit).write(eq(actor), anyString(), anyString(), anyString(), anyString());
    verify(logos, never()).save(any());
  }
}
