package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.auditlog.AuditDetailFormatter;
import com.clavaris.organization.domain.model.Workspace;
import com.clavaris.organization.domain.model.WorkspaceRole;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * The workspaces and roles of an Organization that still exist, by id, so the Audit Log can say
 * "Recruiter" where the stored detail only holds a role id. Kept in this module because the shared
 * audit-log presenter (in {@code common}) knows nothing about workspaces or roles.
 */
final class AuditLogNames {

  private AuditLogNames() {
    // Static helpers only.
  }

  /* default */ static AuditDetailFormatter.Names existing(
      final List<Workspace> workspaces, final List<WorkspaceRole> roles) {
    final Map<String, String> workspaceNames = new HashMap<>();
    workspaces.forEach(
        workspace -> workspaceNames.put(workspace.id().toString(), workspace.name()));
    final Map<String, String> roleNames = new HashMap<>();
    roles.forEach(role -> roleNames.put(role.id().toString(), role.name()));
    return new AuditDetailFormatter.Names(workspaceNames, roleNames);
  }
}
