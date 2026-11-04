package com.clavaris.identity.application.usecase.starttotpenrollment;

import com.clavaris.identity.domain.model.AccountId;

/** {@code issuer} is the name the authenticator app shows beside the account. */
public record StartTotpEnrollmentCommand(AccountId accountId, String issuer) {}
