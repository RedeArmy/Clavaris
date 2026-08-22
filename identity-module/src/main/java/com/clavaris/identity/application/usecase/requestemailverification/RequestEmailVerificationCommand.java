package com.clavaris.identity.application.usecase.requestemailverification;

import com.clavaris.identity.domain.model.AccountId;

/** The account requesting (or re-requesting) verification of its email of record. */
public record RequestEmailVerificationCommand(AccountId accountId) {}
