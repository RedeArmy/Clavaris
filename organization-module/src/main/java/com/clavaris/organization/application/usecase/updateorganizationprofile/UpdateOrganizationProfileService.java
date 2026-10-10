package com.clavaris.organization.application.usecase.updateorganizationprofile;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.OrganizationProfile;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;

public class UpdateOrganizationProfileService implements UpdateOrganizationProfileUseCase {

  private final OrganizationRepository organizations;
  private final OrganizationProfileRepository profiles;
  private final OrganizationLogoRepository logos;
  private final AuditEventRecorder auditEvents;

  public UpdateOrganizationProfileService(
      final OrganizationRepository organizations,
      final OrganizationProfileRepository profiles,
      final OrganizationLogoRepository logos,
      final AuditEventRecorder auditEvents) {
    this.organizations = organizations;
    this.profiles = profiles;
    this.logos = logos;
    this.auditEvents = auditEvents;
  }

  @Override
  @Transactional
  public UpdateOrganizationProfileResult handle(final UpdateOrganizationProfileCommand command) {
    // An Organization that is not the caller's is "not found", exactly like one that does not
    // exist: the dialog must not reveal which ids are real.
    final Organization organization =
        organizations
            .findById(command.organizationId())
            .filter(
                found -> found.ownerPlatformAccountId().equals(command.ownerPlatformAccountId()))
            .orElseThrow(() -> new OrganizationNotFoundException(command.organizationId()));

    final Organization renamed =
        organization.withName(command.name() == null ? null : command.name().strip());
    final OrganizationProfile before =
        profiles
            .findByOrganizationId(command.organizationId())
            .orElseGet(() -> OrganizationProfile.empty(command.organizationId()));
    final OrganizationProfile details =
        before.withDetails(command.description(), command.applicationName(), command.brandColor());

    final List<String> changed = changedFields(organization, renamed, before, details);
    OrganizationProfile after = details;
    if (command.newLogo() != null) {
      logos.save(command.newLogo());
      after = details.withLogoUpdatedAt(Instant.now());
      changed.add("logo");
    } else if (command.removeLogo() && before.hasLogo()) {
      logos.deleteByOrganizationId(command.organizationId());
      after = details.withoutLogo();
      changed.add("logo removed");
    }

    // Saving with nothing to say would create an empty profile row and an audit entry for no
    // change.
    if (!changed.isEmpty()) {
      if (!renamed.name().equals(organization.name())) {
        organizations.save(renamed);
      }
      profiles.save(after);
      // Which fields changed, never their content: a description can be long and is free text.
      auditEvents.write(
          command.actor(),
          "organization.profile_updated",
          "Organization",
          command.organizationId().toString(),
          "changed=" + String.join(",", changed));
    }
    return new UpdateOrganizationProfileResult(renamed, changed.isEmpty() ? before : after);
  }

  private static List<String> changedFields(
      final Organization before,
      final Organization after,
      final OrganizationProfile profileBefore,
      final OrganizationProfile profileAfter) {
    final List<String> changed = new ArrayList<>();
    if (!after.name().equals(before.name())) {
      changed.add("name");
    }
    if (!profileAfter.description().equals(profileBefore.description())) {
      changed.add("description");
    }
    if (!profileAfter.applicationName().equals(profileBefore.applicationName())) {
      changed.add("applicationName");
    }
    if (!profileAfter.brandColor().equals(profileBefore.brandColor())) {
      changed.add("brandColor");
    }
    return changed;
  }
}
