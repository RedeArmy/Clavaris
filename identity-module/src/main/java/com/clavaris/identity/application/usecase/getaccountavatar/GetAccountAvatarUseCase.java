package com.clavaris.identity.application.usecase.getaccountavatar;

import java.util.Optional;

/**
 * Backs the stable {@code GET /o/{organizationId}/avatars/{accountId}} endpoint every OIDC {@code
 * picture} claim this codebase issues points at (ADR-0026) — the one resource in {@code
 * identity-module} deliberately served with no authentication at all, since a browser's own {@code
 * <img src>} can never carry a bearer token.
 */
@FunctionalInterface
public interface GetAccountAvatarUseCase {

  /**
   * Empty means "serve a 404" — unknown Account, or one that doesn't belong to this Organization.
   */
  Optional<AccountAvatarResult> handle(GetAccountAvatarQuery query);
}
