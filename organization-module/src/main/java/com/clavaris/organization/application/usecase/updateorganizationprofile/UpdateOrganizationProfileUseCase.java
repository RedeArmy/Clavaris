package com.clavaris.organization.application.usecase.updateorganizationprofile;

/**
 * Edits an Organization from the dashboard: its name, its description, the consuming application's
 * name, its brand colour and its logo, in one step.
 */
@FunctionalInterface
public interface UpdateOrganizationProfileUseCase {

  /**
   * @throws OrganizationNotFoundException when the Organization does not exist or is not the
   *     caller's
   * @throws IllegalArgumentException when a value is out of range (blank name, over-long text,
   *     malformed colour)
   */
  UpdateOrganizationProfileResult handle(UpdateOrganizationProfileCommand command);
}
