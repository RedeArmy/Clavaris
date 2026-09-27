package com.clavaris.identity.application.usecase.listaccountsfororganization;

import java.util.Collection;
import java.util.Map;
import java.util.UUID;

/**
 * ADR-0029: outbound port — implemented by {@code
 * infrastructure/adapter/out/persistence/JpaWorkspaceRoleDisplayReader}, identity-module's own
 * read-only projection against organization-module's {@code workspace_memberships}/{@code
 * workspace_roles} tables (a data contract, same precedent {@code webhook-module}'s own outbox
 * readers already establish — never organization-module's Java types, never a migration owned
 * here).
 */
@FunctionalInterface
public interface WorkspaceRoleDisplayReader {

  /**
   * @return one entry per {@code accountId} that has a Workspace membership — an id present in
   *     {@code accountIds} but absent from the result belongs to no Workspace yet.
   */
  Map<UUID, WorkspaceRoleDisplay> findByAccountIds(Collection<UUID> accountIds);
}
