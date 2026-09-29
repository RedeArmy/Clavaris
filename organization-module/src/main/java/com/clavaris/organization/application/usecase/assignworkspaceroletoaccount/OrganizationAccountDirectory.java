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
 * ListAccountsForOrganizationUseCase} — see that bridge's own Javadoc for why it loops every page
 * rather than returning just the first one (this port's own contract is "every account," not "a
 * page of them" — the Workspace-detail Teams tab's own "assign a role" picker needs the whole list
 * to filter against, not a paginated slice).
 */
public interface OrganizationAccountDirectory {

  List<OrganizationAccountSummary> listAccountsForOrganization(UUID organizationId);

  /**
   * TD-PERF-027: scoped to a bounded, caller-known set of ids — the Workspace Teams Hierarchy
   * dashboard's own member-label lookup needs labels for a specific handful of accounts, not {@link
   * #listAccountsForOrganization}'s own "every account in the Organization" contract.
   */
  List<OrganizationAccountSummary> listAccountsByIds(UUID organizationId, Set<UUID> accountIds);
}
