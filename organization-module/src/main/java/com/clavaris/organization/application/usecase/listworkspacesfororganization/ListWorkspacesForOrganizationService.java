package com.clavaris.organization.application.usecase.listworkspacesfororganization;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.domain.model.Workspace;
import java.util.ArrayList;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * TD-PERF-032: every workspace of an Organization, for the dashboard screens that genuinely render
 * all of them (the audit log's name lookup, the roles page's team labels, the assign-role dialog's
 * workspace selector). It used to be one unbounded {@code findAll}; it now reads keyset pages of
 * {@link KeysetPageRequest#MAX_SIZE} and stops at {@link #MAX_WORKSPACES}, so a pathological tenant
 * costs a bounded, indexed amount of work and a truncation is logged rather than silent. The REST
 * admin API does not go through here: it pages for its caller.
 */
public class ListWorkspacesForOrganizationService implements ListWorkspacesForOrganizationUseCase {

  private static final Logger LOG =
      LoggerFactory.getLogger(ListWorkspacesForOrganizationService.class);

  /** A hard ceiling on what a dashboard screen will assemble; five full pages. */
  /* package */ static final int MAX_WORKSPACES = 5 * KeysetPageRequest.MAX_SIZE;

  private final WorkspaceRepository workspaces;

  public ListWorkspacesForOrganizationService(final WorkspaceRepository workspaces) {
    this.workspaces = workspaces;
  }

  @Override
  public List<Workspace> handle(final ListWorkspacesForOrganizationQuery query) {
    final List<Workspace> all = new ArrayList<>();
    KeysetPageRequest request = KeysetPageRequest.first(KeysetPageRequest.MAX_SIZE);
    while (true) {
      final KeysetPage<Workspace> page =
          workspaces.findKeysetPageByOrganizationId(query.organizationId(), request);
      all.addAll(page.content());
      if (!page.hasNext()) {
        return all;
      }
      if (all.size() >= MAX_WORKSPACES) {
        LOG.warn(
            "event=workspace_listing_truncated organizationId={} limit={}",
            query.organizationId(),
            MAX_WORKSPACES);
        return all;
      }
      request = KeysetPageRequest.after(page.endCursor(), KeysetPageRequest.MAX_SIZE);
    }
  }
}
