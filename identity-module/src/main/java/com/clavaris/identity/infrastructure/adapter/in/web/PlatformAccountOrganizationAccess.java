package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationQuery;
import com.clavaris.identity.application.usecase.getaccountfororganization.GetAccountForOrganizationUseCase;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.PlatformAccountId;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * SDE-III review, 2026-09-19 — CPD finding: the "resolve the current session's PlatformAccount,
 * confirm it owns this Organization, then resolve the target Account within it (or 404,
 * anti-enumeration throughout)" prologue had drifted into byte-for-byte duplicates across every
 * {@code /platform/dashboard/organizations/{organizationId}/users/**} controller ({@link
 * PlatformAccountsController}, {@link PlatformAccountDetailController}, {@link
 * PlatformAccountAuditLogController}, {@link PlatformAccountImpersonationController}) as each was
 * added. Composed into each controller rather than a base class, consistent with this codebase
 * never using controller inheritance elsewhere.
 */
@SuppressWarnings("PMD.LongVariable")
final class PlatformAccountOrganizationAccess {

  private final OrganizationForPlatformAccountResolver organizationResolver;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  /* package */ PlatformAccountOrganizationAccess(
      final OrganizationForPlatformAccountResolver organizationResolver,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.organizationResolver = organizationResolver;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  /* package */ PlatformAccountId requireCurrentPlatformAccount(final HttpServletRequest request) {
    return CurrentSessionSupport.requireResolved(
        currentPlatformAccount.resolve(request), "PlatformAccount");
  }

  /* package */ String requireOwnedOrganizationName(
      final OrganizationId organizationId, final PlatformAccountId ownerPlatformAccountId) {
    return organizationResolver
        .resolveName(organizationId, ownerPlatformAccountId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }

  /**
   * The full prologue every {@code .../users/{accountId}/**} controller needs: current
   * PlatformAccount → owned Organization → the target Account within it, each step 404-ing
   * identically (anti-enumeration) on any mismatch.
   */
  /* package */ ResolvedAccountAccess requireOwnedAccount(
      final HttpServletRequest request,
      final UUID organizationId,
      final UUID accountId,
      final GetAccountForOrganizationUseCase getAccount) {
    final PlatformAccountId ownerPlatformAccountId = requireCurrentPlatformAccount(request);
    final OrganizationId orgId = new OrganizationId(organizationId);
    final String organizationName = requireOwnedOrganizationName(orgId, ownerPlatformAccountId);
    final Account account =
        getAccount
            .handle(new GetAccountForOrganizationQuery(orgId, new AccountId(accountId)))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    return new ResolvedAccountAccess(ownerPlatformAccountId, orgId, organizationName, account);
  }

  /* package */ record ResolvedAccountAccess(
      PlatformAccountId ownerPlatformAccountId,
      OrganizationId organizationId,
      String organizationName,
      Account account) {}
}
