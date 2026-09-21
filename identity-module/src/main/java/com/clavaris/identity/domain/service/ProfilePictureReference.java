package com.clavaris.identity.domain.service;

/**
 * ADR-0026: {@code Account.pictureUrl} holds one of two shapes, distinguished by this class —
 * either a real external URL (a social provider's own CDN link, captured once at social
 * registration and never re-hosted, per that ADR's own "store the provider's URL directly, don't
 * re-host" decision) or an opaque {@link
 * com.clavaris.identity.application.usecase.updateaccountprofilepicture.ProfilePictureStorage} key
 * for a Clavaris-managed upload. {@code GetAccountAvatarService} and {@code
 * RemoveAccountProfilePictureService} both need this same distinction (redirect vs. proxy-stream;
 * whether there's a storage object to delete at all) — centralized here rather than duplicated.
 */
public final class ProfilePictureReference {

  private ProfilePictureReference() {
    // Static helper only — no instance state.
  }

  public static boolean isExternalUrl(final String pictureUrl) {
    return pictureUrl.startsWith("http://") || pictureUrl.startsWith("https://");
  }
}
