package com.clavaris.identity.application.usecase.getplatformaccountavatar;

import com.clavaris.identity.domain.model.PlatformAccountId;
import java.util.Optional;

/**
 * Backs the stable {@code GET /platform/avatars/{platformAccountId}} endpoint — {@code
 * getaccountavatar.GetAccountAvatarUseCase}'s platform-tier sibling. No Organization to scope by (a
 * {@code PlatformAccount} belongs to none, ADR-0012), so this is simpler than its tenant-tier
 * counterpart — an unknown id is the only "serve a 404" case.
 */
@FunctionalInterface
public interface GetPlatformAccountAvatarUseCase {

  Optional<PlatformAccountAvatarResult> handle(PlatformAccountId platformAccountId);
}
