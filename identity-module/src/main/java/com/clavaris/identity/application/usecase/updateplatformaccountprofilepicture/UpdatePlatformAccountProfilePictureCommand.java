package com.clavaris.identity.application.usecase.updateplatformaccountprofilepicture;

import com.clavaris.identity.domain.model.PlatformAccountId;

/**
 * {@code updateaccountprofilepicture.UpdateAccountProfilePictureCommand}'s platform-tier sibling.
 */
public record UpdatePlatformAccountProfilePictureCommand(
    PlatformAccountId platformAccountId, byte[] content, String contentType) {}
