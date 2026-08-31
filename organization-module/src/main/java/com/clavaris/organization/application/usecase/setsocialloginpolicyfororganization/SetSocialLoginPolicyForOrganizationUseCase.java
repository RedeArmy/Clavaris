package com.clavaris.organization.application.usecase.setsocialloginpolicyfororganization;

/** Inbound port — operator-only in v1, ADR-0020 Decision 3. */
@FunctionalInterface
public interface SetSocialLoginPolicyForOrganizationUseCase {

  /**
   * @throws OrganizationNotFoundException if {@code command.organizationId()} doesn't exist
   * @throws UnknownSocialProviderException if {@code command.providers()} names anything outside
   *     this use case's own known-provider allowlist
   * @throws SocialLoginEnabledWithNoProvidersException if {@code command.enabled()} is {@code true}
   *     with an empty {@code command.providers()} — a real no-op configuration state, rejected
   *     rather than silently persisted (code review finding)
   */
  SetSocialLoginPolicyForOrganizationResult handle(
      SetSocialLoginPolicyForOrganizationCommand command);
}
