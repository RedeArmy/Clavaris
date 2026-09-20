package com.clavaris.identity.application.usecase.removeplatformaccountprofilepicture;

import com.clavaris.identity.application.usecase.updateplatformaccountprofilepicture.PlatformAccountNotFoundException;
import com.clavaris.identity.domain.model.PlatformAccountId;

/**
 * {@code removeaccountprofilepicture.RemoveAccountProfilePictureUseCase}'s platform-tier sibling.
 */
@FunctionalInterface
public interface RemovePlatformAccountProfilePictureUseCase {

  /**
   * @throws PlatformAccountNotFoundException if {@code platformAccountId} doesn't exist
   */
  void handle(PlatformAccountId platformAccountId);
}
