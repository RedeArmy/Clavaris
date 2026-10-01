package com.clavaris.identity.application.usecase.updateaccountmetadata;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.domain.model.Account;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Orchestration for {@link UpdateAccountMetadataUseCase}. Validates each non-blank tier is
 * syntactically valid JSON before it ever reaches {@link Account#setMetadata}/the database - the
 * same "translate a bad input into a typed exception before the domain method runs" posture {@code
 * RegisterAccountService#resolveRawPassword}'s own {@code WeakPasswordException} check already
 * establishes, not something {@link Account} itself should own (it stays free of a JSON library
 * dependency, per its own {@code setMetadata} Javadoc).
 */
// PMD.OnlyOneReturn: validate() has two genuinely distinct exits (nothing to validate vs. a
// validated value to return) - same guard-clause-heavy resolution shape this codebase's own
// comparably-structured methods already document identically elsewhere.
@SuppressWarnings("PMD.OnlyOneReturn")
public class UpdateAccountMetadataService implements UpdateAccountMetadataUseCase {

  // PMD.LongVariable: MAX_METADATA_LENGTH names exactly what it bounds - a shorter name would
  // only make the one call site that references it harder to read.
  // Same order-of-magnitude reasoning as Clerk's own metadata size ceiling - generous enough for
  // real application-level tagging, small enough that a tier can never become a de facto
  // unbounded document store.
  @SuppressWarnings("PMD.LongVariable")
  /* package */ static final int MAX_METADATA_LENGTH = 8192;

  private final AccountRepository accounts;
  private final AuditEventRecorder auditEvents;
  private final ObjectMapper objectMapper;

  public UpdateAccountMetadataService(
      final AccountRepository accounts,
      final AuditEventRecorder auditEvents,
      final ObjectMapper objectMapper) {
    this.accounts = accounts;
    this.auditEvents = auditEvents;
    this.objectMapper = objectMapper;
  }

  @Override
  @Transactional
  public void handle(final UpdateAccountMetadataCommand command) {
    final String publicMetadata = validate("publicMetadata", command.publicMetadata());
    final String privateMetadata = validate("privateMetadata", command.privateMetadata());
    final String unsafeMetadata = validate("unsafeMetadata", command.unsafeMetadata());

    final Account account =
        accounts
            .findById(command.accountId())
            .orElseThrow(() -> new AccountNotFoundException(command.accountId()));

    account.setMetadata(publicMetadata, privateMetadata, unsafeMetadata);
    accounts.save(account);

    // BR-DATA-01: metadata content itself is the consuming application's own data, potentially
    // sensitive - never written into the audit detail, only the fact that it changed.
    auditEvents.write(
        command.actor(),
        "account.metadata_updated",
        "Account",
        account.id().value().toString(),
        null);
  }

  private String validate(final String tier, final String rawJson) {
    if (rawJson == null || rawJson.isBlank()) {
      return null;
    }
    if (rawJson.length() > MAX_METADATA_LENGTH) {
      throw new InvalidMetadataException(
          tier, "exceeds the maximum length of " + MAX_METADATA_LENGTH + " characters");
    }
    try {
      objectMapper.readTree(rawJson);
    } catch (final JacksonException e) {
      throw new InvalidMetadataException(tier, "is not valid JSON", e);
    }
    return rawJson;
  }
}
