package com.clavaris.identity.application.usecase.listwebauthncredentialsforaccount;

import com.clavaris.identity.domain.model.AccountId;

public record ListWebAuthnCredentialsForAccountQuery(AccountId accountId) {}
