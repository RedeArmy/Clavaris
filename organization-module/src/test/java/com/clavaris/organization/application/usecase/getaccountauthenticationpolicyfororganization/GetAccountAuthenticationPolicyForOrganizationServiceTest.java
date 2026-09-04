package com.clavaris.organization.application.usecase.getaccountauthenticationpolicyfororganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.AccountAuthenticationPolicyRepository;
import com.clavaris.organization.domain.model.AccountAuthenticationPolicy;
import com.clavaris.organization.domain.model.EmailVerificationMethod;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class GetAccountAuthenticationPolicyForOrganizationServiceTest {

  @Test
  void returnsTheStoredPolicyWhenOneExists() {
    AccountAuthenticationPolicyRepository policies =
        mock(AccountAuthenticationPolicyRepository.class);
    UUID organizationId = UUID.randomUUID();
    AccountAuthenticationPolicy stored =
        AccountAuthenticationPolicy.define(
            organizationId,
            true,
            EmailVerificationMethod.CODE,
            true,
            false,
            false,
            false,
            false,
            true,
            false);
    when(policies.findByOrganizationId(organizationId)).thenReturn(Optional.of(stored));
    GetAccountAuthenticationPolicyForOrganizationService service =
        new GetAccountAuthenticationPolicyForOrganizationService(policies);

    AccountAuthenticationPolicy result = service.handle(organizationId);

    assertThat(result).isEqualTo(stored);
  }

  @Test
  void returnsDefaultsWhenNoPolicyHasEverBeenSet() {
    AccountAuthenticationPolicyRepository policies =
        mock(AccountAuthenticationPolicyRepository.class);
    UUID organizationId = UUID.randomUUID();
    when(policies.findByOrganizationId(organizationId)).thenReturn(Optional.empty());
    GetAccountAuthenticationPolicyForOrganizationService service =
        new GetAccountAuthenticationPolicyForOrganizationService(policies);

    AccountAuthenticationPolicy result = service.handle(organizationId);

    assertThat(result.passwordAtSignUpEnabled())
        .as(
            "absence of a row must mean the fixed AccountAuthenticationPolicy.defaults(), never a"
                + " null or an exception")
        .isTrue();
    assertThat(result.emailVerificationMethod()).isEqualTo(EmailVerificationMethod.LINK);
  }
}
