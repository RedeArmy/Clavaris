package com.clavaris.identity.application.usecase.requiresecondfactor;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpCredentialRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;

public class RequiresSecondFactorService implements RequiresSecondFactorUseCase {

  private final AccountRepository accounts;
  private final AccountAuthenticationPolicyProvider policyProvider;
  private final TotpCredentialRepository credentials;

  public RequiresSecondFactorService(
      final AccountRepository accounts,
      final AccountAuthenticationPolicyProvider policyProvider,
      final TotpCredentialRepository credentials) {
    this.accounts = accounts;
    this.policyProvider = policyProvider;
    this.credentials = credentials;
  }

  @Override
  public boolean handle(final AccountId accountId) {
    final OrganizationId organizationId = accounts.findOrganizationIdById(accountId).orElse(null);
    return organizationId != null
        && policyProvider.policyFor(organizationId).totpEnabled()
        && credentials.existsConfirmed(accountId);
  }
}
