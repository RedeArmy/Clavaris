package com.clavaris.identity.application.usecase.updateplatformaccountprofilepicture;

import com.clavaris.identity.application.usecase.updateaccountprofilepicture.InvalidProfilePictureException;

/**
 * {@code updateaccountprofilepicture.UpdateAccountProfilePictureUseCase}'s platform-tier sibling.
 */
@FunctionalInterface
public interface UpdatePlatformAccountProfilePictureUseCase {

  /**
   * @throws PlatformAccountNotFoundException if {@code command.platformAccountId()} doesn't exist
   * @throws InvalidProfilePictureException if the content type isn't a supported image format or
   *     the content exceeds the 10MB cap
   */
  UpdatePlatformAccountProfilePictureResult handle(
      UpdatePlatformAccountProfilePictureCommand command);
}
