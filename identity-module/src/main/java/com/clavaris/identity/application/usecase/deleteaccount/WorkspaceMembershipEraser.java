package com.clavaris.identity.application.usecase.deleteaccount;

import com.clavaris.identity.domain.model.AccountId;

/**
 * Outbound port (ADR-0007: "workspace-membership removal on account deletion... performed
 * synchronously, inside the same use-case transaction, via direct calls to the other module's own
 * port"). Deliberately does NOT reference {@code WorkspaceMembership} or any organization-module
 * type — identity-module and organization-module stay mutually independent business modules, same
 * convention {@code AccountTokenRevoker}/{@code AccountSessionRevoker} already established for
 * their own cross-cutting concerns. Implemented in {@code app}, the one module allowed to depend on
 * both, by {@code WorkspaceMembershipEraserBridge}.
 *
 * <p>Closes the gap this class's own caller ({@link DeleteAccountService}) used to document
 * explicitly: before this port existed, a hard-deleted {@code Account} left every {@code
 * WorkspaceMembership} row that referenced it dangling — {@code Workspace} didn't exist in this
 * codebase yet when {@code DeleteAccountService} first shipped, so there was nothing to erase.
 */
@FunctionalInterface
public interface WorkspaceMembershipEraser {

  void eraseAllMembershipsFor(AccountId accountId);
}
