package com.clavaris.organization.application.usecase.checkaccessrestrictionfororganization;

@FunctionalInterface
public interface CheckAccessRestrictionForOrganizationUseCase {

  /**
   * @return true if {@code query.email()} is allowed to register/be created for this Organization.
   */
  boolean handle(CheckAccessRestrictionForOrganizationQuery query);
}
