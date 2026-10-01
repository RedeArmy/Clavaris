package com.clavaris.identity.application.usecase.authenticatewithwebauthn;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.registerwebauthncredential.WebAuthnCredentialRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.WebAuthnCredential;
import com.yubico.webauthn.AssertionResult;
import com.yubico.webauthn.FinishAssertionOptions;
import com.yubico.webauthn.RelyingParty;
import com.yubico.webauthn.data.AuthenticatorAssertionResponse;
import com.yubico.webauthn.data.ClientAssertionExtensionOutputs;
import com.yubico.webauthn.data.PublicKeyCredential;
import com.yubico.webauthn.exception.AssertionFailedException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Orchestration for {@link AuthenticateWithWebAuthnUseCase} — structural twin of {@code
 * AuthenticateWithUsernameService}/{@code AuthenticateWithEmailCodeService} (same active-status
 * check, same {@code recordSignIn()}/{@code save()} call this session's own prior bug fix
 * established as mandatory for every primary-factor service), differing only in how the {@link
 * Account} is resolved: the WebAuthn "user handle" {@link RelyingParty#finishAssertion} returns,
 * not an email/username lookup.
 */
// PMD.PreserveStackTrace: deliberate — same anti-enumeration collapsing rationale as
// InvalidCredentialsException's own no-arg, no-cause shape: a parse failure, a failed assertion
// verification, an unresolvable Account, and an inactive Account must all be indistinguishable to
// the caller, including via a leaked stack trace that could hint at which one actually happened.
// PMD.GuardLogStatement: same precedent AuthenticateWithUsernameService's own identical
// suppression documents — these are INFO-level security/audit log lines, not a hot path. PMD.
// LawOfDemeter: reading fields off Yubico's own AssertionResult/RegisteredCredential DTOs is the
// entire point of this class — same "working with a vetted library's own result type" rationale
// CompleteWebAuthnRegistrationService's own identical suppression documents.
@SuppressWarnings({"PMD.PreserveStackTrace", "PMD.GuardLogStatement", "PMD.LawOfDemeter"})
public class AuthenticateWithWebAuthnService implements AuthenticateWithWebAuthnUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(AuthenticateWithWebAuthnService.class);

  private final RelyingParty relyingParty;
  private final WebAuthnCredentialRepository credentials;
  private final AccountRepository accounts;

  public AuthenticateWithWebAuthnService(
      final RelyingParty relyingParty,
      final WebAuthnCredentialRepository credentials,
      final AccountRepository accounts) {
    this.relyingParty = relyingParty;
    this.credentials = credentials;
    this.accounts = accounts;
  }

  @Override
  public Account handle(final AuthenticateWithWebAuthnCommand command) {
    final PublicKeyCredential<AuthenticatorAssertionResponse, ClientAssertionExtensionOutputs>
        response;
    try {
      response = PublicKeyCredential.parseAssertionResponseJson(command.credentialJson());
    } catch (final IOException e) {
      LOG.info("event=login_failure reason=malformed_webauthn_assertion");
      throw new InvalidWebAuthnAssertionException();
    }

    final AssertionResult result;
    try {
      result =
          relyingParty.finishAssertion(
              FinishAssertionOptions.builder()
                  .request(command.request())
                  .response(response)
                  .build());
    } catch (final AssertionFailedException e) {
      LOG.info("event=login_failure reason=webauthn_assertion_failed");
      throw new InvalidWebAuthnAssertionException();
    }

    final AccountId accountId = toAccountId(result.getUserHandle().getBytes());
    final Optional<Account> found = accounts.findById(accountId);
    if (found.isEmpty()) {
      LOG.info("event=login_failure reason=unknown_account_for_webauthn_handle");
      throw new InvalidWebAuthnAssertionException();
    }
    final Account account = found.get();

    if (account.status() != AccountStatus.ACTIVE) {
      LOG.info(
          "event=login_failure accountId={} reason=inactive_account_for_webauthn", account.id());
      throw new InvalidWebAuthnAssertionException();
    }

    // Bump the counter against the actual stored row, resolved by the credential id the assertion
    // itself names — result.getCredentialId() is the raw WebAuthn credential id, not this
    // repository's own surrogate row id.
    credentials
        .findByCredentialId(result.getCredentialId().getBytes())
        .map(WebAuthnCredential::id)
        .ifPresent(
            credentialRowId ->
                credentials.updateSignatureCount(
                    credentialRowId, result.getSignatureCount(), Instant.now()));

    account.recordSignIn();
    accounts.save(account);

    LOG.info("event=login_success accountId={} factor=webauthn", account.id());
    return account;
  }

  private static AccountId toAccountId(final byte[] userHandle) {
    try {
      return new AccountId(UUID.fromString(new String(userHandle, StandardCharsets.UTF_8)));
    } catch (final IllegalArgumentException e) {
      LOG.info("event=login_failure reason=malformed_webauthn_user_handle");
      throw new InvalidWebAuthnAssertionException();
    }
  }
}
