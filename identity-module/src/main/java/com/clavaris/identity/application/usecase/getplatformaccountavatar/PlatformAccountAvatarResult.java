package com.clavaris.identity.application.usecase.getplatformaccountavatar;

/** {@code getaccountavatar.AccountAvatarResult}'s platform-tier sibling. */
public sealed interface PlatformAccountAvatarResult {

  record Redirect(String externalUrl) implements PlatformAccountAvatarResult {}

  record Content(byte[] content, String contentType) implements PlatformAccountAvatarResult {}
}
