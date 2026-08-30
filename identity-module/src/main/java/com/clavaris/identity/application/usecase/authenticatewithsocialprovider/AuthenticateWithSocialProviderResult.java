package com.clavaris.identity.application.usecase.authenticatewithsocialprovider;

import com.clavaris.identity.domain.model.AccountId;

/**
 * ADR-0020 Decision 1: a social login attempt ends in one of two structurally different outcomes,
 * not one result with a nullable field — an already-linked identity or a brand-new signup logs the
 * caller in immediately; an existing account reached via a different method never logs in on this
 * request at all, regardless of how "verified" the provider's email claim is (that's the entire
 * point of requiring an explicit confirmation step). A sealed interface makes the caller (the web
 * adapter) handle both cases explicitly rather than guessing from a null {@code accountId}.
 */
public sealed interface AuthenticateWithSocialProviderResult {

  record LoggedIn(AccountId accountId) implements AuthenticateWithSocialProviderResult {}

  /**
   * A {@code PendingSocialLink} was raised and a confirmation email sent to the account's email of
   * record — the caller must show a "check your email" response, never a session/token.
   */
  record ConfirmationRequired() implements AuthenticateWithSocialProviderResult {}
}
