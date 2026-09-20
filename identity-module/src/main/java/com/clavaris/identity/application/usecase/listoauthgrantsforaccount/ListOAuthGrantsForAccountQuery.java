package com.clavaris.identity.application.usecase.listoauthgrantsforaccount;

import com.clavaris.identity.domain.model.AccountId;

public record ListOAuthGrantsForAccountQuery(AccountId accountId) {}
