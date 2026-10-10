package com.clavaris.organization.application.usecase.updateorganizationprofile;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.domain.model.OrganizationLogo;
import java.util.UUID;

/**
 * Everything the edit dialog sets on an Organization at once.
 *
 * @param ownerPlatformAccountId who is asking: the Organization must be theirs
 * @param name the Organization's name; required
 * @param description shown on its dashboard card; blank means none
 * @param applicationName the consuming application's name; blank means none
 * @param brandColor {@code #rrggbb}; blank means none
 * @param newLogo an already validated image to store, or {@code null} to leave the logo as it is
 * @param removeLogo remove the current logo; ignored when {@code newLogo} is given
 */
@SuppressWarnings("PMD.LongVariable")
public record UpdateOrganizationProfileCommand(
    UUID organizationId,
    UUID ownerPlatformAccountId,
    String name,
    String description,
    String applicationName,
    String brandColor,
    OrganizationLogo newLogo,
    boolean removeLogo,
    AuditActor actor) {}
