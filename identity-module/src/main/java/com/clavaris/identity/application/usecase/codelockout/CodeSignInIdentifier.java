package com.clavaris.identity.application.usecase.codelockout;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.Username;
import java.util.Locale;
import java.util.Optional;

/**
 * What a person typed into the one field of a sign-in by emailed code: an email, or, where the
 * Organization has {@code usernameSignInEnabled}, a username (ADR-0032's rule, for the code flow).
 * The choice is made by shape alone ({@code @} is in every email and in no username), so it tells
 * nothing about what exists. A value that is neither, or a username where username sign-in is off,
 * is simply "no account": the caller answers it exactly like an unknown address.
 *
 * <p>It also names the subject the wrong codes are counted under (ADR-0033). An account found by
 * username is counted under its own email, so the two ways of naming one account share one counter
 * and a second identifier does not double the guesses; an identifier with no account is counted
 * under what was typed, so an unknown name is paused the same as a known one and a pause reveals
 * nothing.
 */
public final class CodeSignInIdentifier {

  private static final char EMAIL_MARKER = '@';

  private final Email email;
  private final Username username;
  private final String typed;

  private CodeSignInIdentifier(final Email email, final Username username, final String typed) {
    this.email = email;
    this.username = username;
    this.typed = typed;
  }

  /**
   * @param rawIdentifier as typed; null or blank is accepted and means no account
   * @param usernameSignInEnabled the Organization's {@code usernameSignInEnabled}
   */
  public static CodeSignInIdentifier of(
      final String rawIdentifier, final boolean usernameSignInEnabled) {
    final String stripped = rawIdentifier == null ? "" : rawIdentifier.strip();
    try {
      if (stripped.indexOf(EMAIL_MARKER) >= 0) {
        final Email parsed = new Email(stripped);
        return new CodeSignInIdentifier(parsed, null, parsed.value());
      }
      if (usernameSignInEnabled) {
        final Username parsed = new Username(stripped);
        return new CodeSignInIdentifier(null, parsed, "username:" + parsed.value());
      }
    } catch (final IllegalArgumentException _) {
      return unnamed(stripped);
    }
    return unnamed(stripped);
  }

  // Neither a valid email nor a valid username, or a username where that is off: no account,
  // counted
  // under what was typed so it is paused like any other subject.
  private static CodeSignInIdentifier unnamed(final String stripped) {
    return new CodeSignInIdentifier(null, null, "invalid:" + stripped.toLowerCase(Locale.ROOT));
  }

  /** The account this names in the Organization, if there is one. */
  public Optional<Account> find(final AccountRepository accounts, final OrganizationId org) {
    if (email != null) {
      return accounts.findByOrganizationIdAndEmail(org, email);
    }
    if (username != null) {
      return accounts.findByOrganizationIdAndUsername(org, username);
    }
    return Optional.empty();
  }

  /** The key the wrong codes are counted under: the account's email once it is known. */
  public String lockoutKey(final OrganizationId org, final Optional<Account> account) {
    return CodeLockout.signInCodeKey(
        org, account.map(found -> found.email().value()).orElse(typed));
  }
}
