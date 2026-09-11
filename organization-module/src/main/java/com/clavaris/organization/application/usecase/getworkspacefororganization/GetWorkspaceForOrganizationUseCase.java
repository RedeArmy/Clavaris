package com.clavaris.organization.application.usecase.getworkspacefororganization;

import com.clavaris.organization.domain.model.Workspace;
import java.util.Optional;

/**
 * ADR-0025: the dashboard's own "does this Workspace belong to the Organization named in this URL"
 * check — {@code PlatformWorkspaceController} resolves {@code organizationId} as
 * owned-by-the-caller first (via {@code GetOrganizationForPlatformAccountUseCase}), then uses this
 * use case to confirm {@code workspaceId} actually lives inside that same Organization before
 * acting on it. Same "unknown and not-yours look identical, a plain 404" anti-enumeration posture
 * {@code GetOrganizationForPlatformAccountUseCase}'s own Javadoc documents — a Workspace that
 * exists but belongs to a different Organization is empty here, never a distinguishable exception.
 */
@FunctionalInterface
public interface GetWorkspaceForOrganizationUseCase {

  Optional<Workspace> handle(GetWorkspaceForOrganizationQuery query);
}
