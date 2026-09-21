package com.clavaris.identity.application.usecase.getaccountavatar;

import com.clavaris.identity.application.usecase.registeraccount.AccountRepository;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.ProfilePictureStorage;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.StoredProfilePicture;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.service.InitialsAvatarGenerator;
import com.clavaris.identity.domain.service.ProfilePictureReference;
import java.util.Optional;

/**
 * Orchestration for {@link GetAccountAvatarUseCase}. ADR-0026: {@code organizationId} mismatch is
 * treated identically to "unknown Account" (both return {@link Optional#empty()}) — a public,
 * unauthenticated endpoint must never distinguish "exists in a different Organization" from
 * "doesn't exist at all", the same information-leak posture every other cross-tenant lookup in this
 * codebase already takes.
 *
 * <p>The generated-initials default avatar (no {@code pictureUrl} set at all — every email/password
 * registration, per this feature's own product decision) is {@link InitialsAvatarGenerator} —
 * shared with {@code GetPlatformAccountAvatarService}, not duplicated here. Deliberately never
 * user-uploaded content: an uploaded picture is always served as whatever binary format it actually
 * is ({@link UpdateAccountProfilePictureService}'s own content-type allow-list excludes {@code
 * image/svg+xml} for exactly this reason — an SVG can embed script, safe only when Clavaris itself
 * is the one generating it).
 */
public class GetAccountAvatarService implements GetAccountAvatarUseCase {

  private final AccountRepository accounts;
  private final ProfilePictureStorage storage;

  public GetAccountAvatarService(
      final AccountRepository accounts, final ProfilePictureStorage storage) {
    this.accounts = accounts;
    this.storage = storage;
  }

  // PMD.OnlyOneReturn: each early exit is a real, distinct outcome (unknown Account, wrong
  // Organization, generated default, external redirect, stored upload) — same "each outcome
  // needs its own exit" rationale SetRateLimitPolicyController's own identical suppression
  // documents.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Override
  public Optional<AccountAvatarResult> handle(final GetAccountAvatarQuery query) {
    final Optional<Account> maybeAccount = accounts.findById(query.accountId());
    if (maybeAccount.isEmpty()) {
      return Optional.empty();
    }
    final Account account = maybeAccount.get();
    if (!account.organizationId().equals(query.organizationId())) {
      return Optional.empty();
    }

    final Optional<String> pictureUrl = account.pictureUrl();
    if (pictureUrl.isEmpty()) {
      final byte[] svg =
          InitialsAvatarGenerator.generateSvg(
              account.id().value(),
              account.firstName(),
              account.lastName(),
              account.email().value());
      return Optional.of(new AccountAvatarResult.Content(svg, "image/svg+xml"));
    }
    if (ProfilePictureReference.isExternalUrl(pictureUrl.get())) {
      return Optional.of(new AccountAvatarResult.Redirect(pictureUrl.get()));
    }
    final StoredProfilePicture stored = storage.download(pictureUrl.get());
    return Optional.of(new AccountAvatarResult.Content(stored.content(), stored.contentType()));
  }
}
