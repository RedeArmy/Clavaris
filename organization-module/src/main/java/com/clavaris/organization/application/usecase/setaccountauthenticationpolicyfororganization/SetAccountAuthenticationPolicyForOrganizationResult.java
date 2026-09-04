package com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization;

import com.clavaris.organization.domain.model.AccountAuthenticationPolicy;

public record SetAccountAuthenticationPolicyForOrganizationResult(
    AccountAuthenticationPolicy policy) {}
