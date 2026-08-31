package com.clavaris.identity.application.usecase.confirmpendingplatformsociallink;

/**
 * @param presentedRawToken same convention as {@code
 *     confirmpendingsociallink.ConfirmPendingSocialLinkCommand}.
 */
public record ConfirmPendingPlatformSocialLinkCommand(String presentedRawToken) {}
