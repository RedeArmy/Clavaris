package com.clavaris.identity.application.usecase.getaccountstatus;

import com.clavaris.identity.domain.model.AccountId;

/** TD-FUT-081: which Account's status to read. */
public record GetAccountStatusQuery(AccountId accountId) {}
