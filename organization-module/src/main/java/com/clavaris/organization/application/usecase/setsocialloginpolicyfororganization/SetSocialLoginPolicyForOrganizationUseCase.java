package com.clavaris.organization.application.usecase.setsocialloginpolicyfororganization;

/** Inbound port — operator-only in v1, ADR-0020 Decision 3. */
@FunctionalInterface
public interface SetSocialLoginPolicyForOrganizationUseCase {

  /**
   * @throws OrganizationNotFoundException if {@code command.organizationId()} doesn't exist
   * @throws UnknownSocialProviderException if {@code command.providers()} names anything outside
   *     this use case's own known-provider allowlist
   */
  SetSocialLoginPolicyForOrganizationResult handle(
      SetSocialLoginPolicyForOrganizationCommand command);
}
