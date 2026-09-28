package com.clavaris.app.infrastructure.adapter.out.bridge;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.identity.application.usecase.listaccountsfororganization.ListAccountsForOrganizationQuery;
import com.clavaris.identity.application.usecase.listaccountsfororganization.ListAccountsForOrganizationUseCase;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.OrganizationAccountDirectory;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.OrganizationAccountSummary;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Implements organization-module's {@link OrganizationAccountDirectory} — the bridge lives in
 * {@code app}, not either business module, same module-graph reason {@link
 * WorkspaceMemberAccountProvisionerBridge} already establishes.
 *
 * <p>Loops every page of identity-module's own {@link ListAccountsForOrganizationUseCase} (at its
 * {@link KeysetPageRequest#MAX_SIZE} page size) rather than returning just the first — this port's
 * contract is "every account in the Organization" for a picker to filter against, not a paginated
 * slice a dashboard list would render. Acceptable here (unlike a real user-facing list) because
 * this only backs admin surfaces (the "assign a role" picker, and — 2026-09-28 — the Teams tab's
 * own hierarchy display), not a page a caller waits on interactively for a large Organization; see
 * this port's own Javadoc.
 *
 * <p>{@code label}, 2026-09-28 (live UX request): the Teams hierarchy's own member list used to
 * render a bare {@code accountId} — real gap, organization-module has no access to
 * identity-module's {@code Account} type at all (module boundary), so a friendly label was never
 * available there without this exact port. {@code firstName}/{@code lastName} (BR-ID-01: both
 * optional profile attributes, {@code email} is the only mandatory identity field) are preferred
 * when either is present; {@code email} alone is the fallback — same "full name, or the one thing
 * every Account is guaranteed to have" reasoning {@code organization-users.html}'s own {@code
 * fullName}/email columns already establish for an identical display problem, one level up in
 * identity-module itself.
 */
@Component
class OrganizationAccountDirectoryBridge implements OrganizationAccountDirectory {

  private final ListAccountsForOrganizationUseCase listAccounts;

  /* package */ OrganizationAccountDirectoryBridge(
      final ListAccountsForOrganizationUseCase listAccounts) {
    this.listAccounts = listAccounts;
  }

  @Override
  public List<OrganizationAccountSummary> listAccountsForOrganization(final UUID organizationId) {
    final OrganizationId orgId = new OrganizationId(organizationId);
    final List<OrganizationAccountSummary> summaries = new ArrayList<>();

    KeysetPageRequest pageRequest = KeysetPageRequest.first(KeysetPageRequest.MAX_SIZE);
    while (true) {
      final KeysetPage<Account> page =
          listAccounts.handle(new ListAccountsForOrganizationQuery(orgId, pageRequest, null));
      for (final Account account : page.content()) {
        summaries.add(new OrganizationAccountSummary(account.id().value(), labelFor(account)));
      }
      if (!page.hasNext()) {
        break;
      }
      pageRequest = KeysetPageRequest.after(page.endCursor(), KeysetPageRequest.MAX_SIZE);
    }

    return List.copyOf(summaries);
  }

  private static String labelFor(final Account account) {
    final String fullName =
        (account.firstName().orElse("") + " " + account.lastName().orElse("")).trim();
    return fullName.isEmpty() ? account.email().value() : fullName;
  }
}
