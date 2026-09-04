package com.clavaris.organization.application.usecase.createproductionenvironment;

/**
 * SDE-III feature build, 2026-09-04 (Clerk Development/Production instances analysis) — {@code POST
 * /api/v1/admin/organizations/{id}:create-production-environment}: promotes an existing {@code
 * DEVELOPMENT} Organization by creating its paired {@code PRODUCTION} sibling, reusing {@code
 * createorganization.CreateOrganizationService}'s own signing-key provisioning/auditing shape (see
 * {@link CreateProductionEnvironmentService}'s own Javadoc). Deliberately does not clone any {@code
 * OAuthClient}/{@code Workspace} configuration from the source Organization — an operator registers
 * new clients under the production Organization the same way as for any other, through the
 * already-existing endpoints; cloning is named, not silently attempted, as a real, deliberately
 * deferred follow-up (see {@code technical-debt-register.md}).
 */
@FunctionalInterface
public interface CreateProductionEnvironmentUseCase {

  /**
   * @throws OrganizationNotFoundException if {@code command.developmentOrganizationId()} doesn't
   *     resolve
   * @throws OrganizationNotDevelopmentException if that Organization is not a {@code DEVELOPMENT}
   *     environment
   * @throws OrganizationAlreadyHasLinkedEnvironmentException if it already has a paired sibling
   */
  CreateProductionEnvironmentResult handle(CreateProductionEnvironmentCommand command);
}
