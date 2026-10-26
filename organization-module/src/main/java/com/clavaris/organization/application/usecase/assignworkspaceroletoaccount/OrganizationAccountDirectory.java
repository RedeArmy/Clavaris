package com.clavaris.organization.application.usecase.assignworkspaceroletoaccount;

import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Outbound port — identity-module owns {@code Account} (email, name); organization-module never
 * references its type directly, same convention {@code AccountProvisioner}'s own Javadoc documents
 * for an identical cross-module boundary (module-graph hexagonal dependency rule: neither
 * organization-module nor identity-module has a Maven dependency on the other). Backed by an
 * app-module bridge delegating to identity-module's own already-built {@code
 * ListAccountsForOrganizationUseCase}.
 */
public interface OrganizationAccountDirectory {

  /**
   * TD-PERF-042: a bounded search, not "every account". The newest {@code limit} accounts of the
   * Organization whose name, username or email contains {@code term} (all of them, newest first,
   * when {@code term} is blank), at most {@code limit}, which must be 1 to 100. The "assign a role"
   * picker used to page the whole account pool into memory and render it as one {@code <select>};
   * it now asks for what the operator typed.
   */
  List<OrganizationAccountSummary> searchAccounts(UUID organizationId, String term, int limit);

  /**
   * TD-PERF-027: scoped to a bounded, caller-known set of ids — the Workspace Teams Hierarchy
   * dashboard's own member-label lookup needs labels for a specific handful of accounts, not a
   * search over the whole Organization.
   */
  List<OrganizationAccountSummary> listAccountsByIds(UUID organizationId, Set<UUID> accountIds);
}
