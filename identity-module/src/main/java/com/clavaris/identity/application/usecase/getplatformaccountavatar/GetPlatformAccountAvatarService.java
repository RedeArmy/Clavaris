package com.clavaris.identity.application.usecase.getplatformaccountavatar;

import com.clavaris.identity.application.usecase.registerplatformaccount.PlatformAccountRepository;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.ProfilePictureStorage;
import com.clavaris.identity.application.usecase.updateaccountprofilepicture.StoredProfilePicture;
import com.clavaris.identity.domain.model.PlatformAccount;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.service.InitialsAvatarGenerator;
import com.clavaris.identity.domain.service.ProfilePictureReference;
import java.util.Optional;

/**
 * Orchestration for {@link GetPlatformAccountAvatarUseCase} — {@code
 * getaccountavatar.GetAccountAvatarService}'s platform-tier sibling, same shape minus the
 * Organization-scoping check that class's own Javadoc explains (not applicable here at all).
 */
public class GetPlatformAccountAvatarService implements GetPlatformAccountAvatarUseCase {

  private final PlatformAccountRepository accounts;
  private final ProfilePictureStorage storage;

  public GetPlatformAccountAvatarService(
      final PlatformAccountRepository accounts, final ProfilePictureStorage storage) {
    this.accounts = accounts;
    this.storage = storage;
  }

  // PMD.OnlyOneReturn: each early exit is a real, distinct outcome — same rationale
  // GetAccountAvatarService's own identical suppression documents.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @Override
  public Optional<PlatformAccountAvatarResult> handle(final PlatformAccountId platformAccountId) {
    final Optional<PlatformAccount> maybeAccount = accounts.findById(platformAccountId);
    if (maybeAccount.isEmpty()) {
      return Optional.empty();
    }
    final PlatformAccount account = maybeAccount.get();

    final Optional<String> pictureUrl = account.pictureUrl();
    if (pictureUrl.isEmpty()) {
      final byte[] svg =
          InitialsAvatarGenerator.generateSvg(
              account.id().value(),
              account.firstName(),
              account.lastName(),
              account.email().value());
      return Optional.of(new PlatformAccountAvatarResult.Content(svg, "image/svg+xml"));
    }
    if (ProfilePictureReference.isExternalUrl(pictureUrl.get())) {
      return Optional.of(new PlatformAccountAvatarResult.Redirect(pictureUrl.get()));
    }
    final StoredProfilePicture stored = storage.download(pictureUrl.get());
    return Optional.of(
        new PlatformAccountAvatarResult.Content(stored.content(), stored.contentType()));
  }
}
