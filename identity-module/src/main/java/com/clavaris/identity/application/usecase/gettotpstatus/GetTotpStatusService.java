package com.clavaris.identity.application.usecase.gettotpstatus;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpCredentialRepository;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpRecoveryCodeRepository;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;

public class GetTotpStatusService implements GetTotpStatusUseCase {

  private final AccountRepository accounts;
  private final AccountAuthenticationPolicyProvider policyProvider;
  private final TotpCredentialRepository credentials;
  private final TotpRecoveryCodeRepository recoveryCodes;

  public GetTotpStatusService(
      final AccountRepository accounts,
      final AccountAuthenticationPolicyProvider policyProvider,
      final TotpCredentialRepository credentials,
      final TotpRecoveryCodeRepository recoveryCodes) {
    this.accounts = accounts;
    this.policyProvider = policyProvider;
    this.credentials = credentials;
    this.recoveryCodes = recoveryCodes;
  }

  @Override
  public TotpStatus handle(final AccountId accountId) {
    final OrganizationId organizationId = accounts.findOrganizationIdById(accountId).orElse(null);
    if (organizationId == null) {
      return new TotpStatus(false, false, 0);
    }
    final boolean available = policyProvider.policyFor(organizationId).totpEnabled();
    final boolean enrolled = credentials.existsConfirmed(accountId);
    return new TotpStatus(available, enrolled, enrolled ? recoveryCodes.countUnused(accountId) : 0);
  }
}
