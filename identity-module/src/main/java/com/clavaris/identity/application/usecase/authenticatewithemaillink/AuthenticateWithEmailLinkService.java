package com.clavaris.identity.application.usecase.authenticatewithemaillink;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.requestemailverification.VerificationTokenRepository;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.VerificationToken;
import com.clavaris.identity.domain.model.VerificationTokenType;
import com.clavaris.identity.domain.service.RefreshTokenSecret;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.transaction.annotation.Transactional;

/**
 * Orchestration for {@link AuthenticateWithEmailLinkUseCase} — ADR-0024 §3, the link counterpart to
 * {@code AuthenticateWithEmailCodeService}; see that class's own Javadoc for the shared reasoning
 * (email-verified side effect, why no {@code emailVerificationRequiredAtSignIn} check here). No
 * HTTP-layer brute-force rule is needed here the way the code flow needs one — the presented value
 * is an unguessable 256-bit token, not a 6-digit code.
 */
public class AuthenticateWithEmailLinkService implements AuthenticateWithEmailLinkUseCase {

  private static final Logger LOG = LoggerFactory.getLogger(AuthenticateWithEmailLinkService.class);

  private final AccountRepository accounts;
  private final VerificationTokenRepository tokens;

  public AuthenticateWithEmailLinkService(
      final AccountRepository accounts, final VerificationTokenRepository tokens) {
    this.accounts = accounts;
    this.tokens = tokens;
  }

  @SuppressWarnings("PMD.GuardLogStatement")
  @Override
  @Transactional
  public AccountId handle(final AuthenticateWithEmailLinkCommand command) {
    final String presentedHash = RefreshTokenSecret.hash(command.presentedRawToken());
    final Optional<VerificationToken> found = tokens.findByTokenHash(presentedHash);
    if (found.isEmpty()
        || found.get().type() != VerificationTokenType.EMAIL_SIGN_IN_LINK
        || !found.get().isActive()) {
      LOG.info(
          "event=email_link_sign_in_failure organizationId={} reason=invalid_or_expired_link",
          command.organizationId());
      throw new InvalidSignInLinkException();
    }

    final VerificationToken token = found.get();
    final Account account =
        accounts.findById(token.accountId()).orElseThrow(InvalidSignInLinkException::new);

    // BR-ORG-02: the resolved account must belong to the Organization this URL names — see
    // AuthenticateWithEmailCodeService's own identical check for the full rationale.
    if (!account.organizationId().equals(command.organizationId())) {
      LOG.info(
          "event=email_link_sign_in_failure organizationId={} accountId={}"
              + " reason=organization_mismatch",
          command.organizationId(),
          account.id());
      throw new InvalidSignInLinkException();
    }

    if (account.status() != AccountStatus.ACTIVE) {
      LOG.info(
          "event=email_link_sign_in_failure organizationId={} accountId={} reason=inactive_account",
          command.organizationId(),
          account.id());
      throw new InvalidSignInLinkException();
    }

    token.consume();
    tokens.save(token);
    account.verifyEmail();
    accounts.save(account);

    LOG.info(
        "event=email_link_sign_in_success organizationId={} accountId={}",
        command.organizationId(),
        account.id());
    return account.id();
  }
}
