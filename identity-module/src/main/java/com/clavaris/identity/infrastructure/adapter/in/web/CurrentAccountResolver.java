package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.domain.model.AccountId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.Optional;

/**
 * Outbound port — implemented in {@code app}, using real Spring Security machinery (reading the
 * current {@code SecurityContext}) that identity-module deliberately does not depend on.
 * Tenant-tier mirror of organization-module's {@code CurrentPlatformAccountResolver} — same shape,
 * checks {@code ROLE_ACCOUNT} instead of {@code ROLE_PLATFORM_ACCOUNT}.
 *
 * <p>Empty, not thrown, for an unauthenticated or wrong-tier request — {@link
 * AccountSessionsController}'s own security filter chain already guarantees this is never actually
 * reached that way, but an honest {@code Optional} return, not an exception, matches {@code
 * CurrentPlatformAccountResolver}'s own convention for the same "nothing resolved" case.
 */
@FunctionalInterface
public interface CurrentAccountResolver {

  Optional<AccountId> resolve(HttpServletRequest request);
}
