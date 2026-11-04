package com.clavaris.identity.application.usecase.requiresecondfactor;

import com.clavaris.identity.domain.model.AccountId;

/**
 * ADR-0040: whether signing this Account in needs its authenticator code. True only when the
 * Organization has the authenticator app switched on <em>and</em> the Account has confirmed one:
 * turning the switch off stops asking without deleting anything, and an Account that never added an
 * app is never locked out by an Organization turning it on.
 */
@FunctionalInterface
public interface RequiresSecondFactorUseCase {

  boolean handle(AccountId accountId);
}
