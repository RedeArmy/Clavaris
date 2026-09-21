package com.clavaris.identity.application.usecase.updateaccountprofilepicture;

/**
 * Self-service (or operator-initiated) profile picture upload — the pasted "Update profile,
 * recommended size 1:1, up to 10MB" UI copy this feature implements. See {@link
 * UpdateAccountProfilePictureCommand}'s own Javadoc for the exact size/content-type limits
 * enforced.
 */
@FunctionalInterface
public interface UpdateAccountProfilePictureUseCase {

  /**
   * @throws AccountNotFoundException if {@code command.accountId()} doesn't exist
   * @throws InvalidProfilePictureException if the content type isn't a supported image format or
   *     the content exceeds the 10MB cap
   */
  UpdateAccountProfilePictureResult handle(UpdateAccountProfilePictureCommand command);
}
