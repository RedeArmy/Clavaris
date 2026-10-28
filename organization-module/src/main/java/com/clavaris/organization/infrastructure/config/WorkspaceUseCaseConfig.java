package com.clavaris.organization.infrastructure.config;

import com.clavaris.common.application.port.AuditEventRecorder;
import com.clavaris.organization.application.usecase.addroletoworkspaceteam.AddRoleToWorkspaceTeamService;
import com.clavaris.organization.application.usecase.addroletoworkspaceteam.AddRoleToWorkspaceTeamUseCase;
import com.clavaris.organization.application.usecase.addworkspacemember.AccountProvisioner;
import com.clavaris.organization.application.usecase.addworkspacemember.AddWorkspaceMemberService;
import com.clavaris.organization.application.usecase.addworkspacemember.AddWorkspaceMemberUseCase;
import com.clavaris.organization.application.usecase.addworkspacemember.WorkspaceMembershipRepository;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.AssignWorkspaceRoleToAccountService;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.AssignWorkspaceRoleToAccountUseCase;
import com.clavaris.organization.application.usecase.assignworkspaceroletoaccount.OrganizationAccountDirectory;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.ChangeWorkspaceMemberRoleService;
import com.clavaris.organization.application.usecase.changeworkspacememberrole.ChangeWorkspaceMemberRoleUseCase;
import com.clavaris.organization.application.usecase.createorganization.OrganizationRepository;
import com.clavaris.organization.application.usecase.createworkspace.CreateWorkspaceService;
import com.clavaris.organization.application.usecase.createworkspace.CreateWorkspaceUseCase;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRoleRepository;
import com.clavaris.organization.application.usecase.createworkspacerole.CreateWorkspaceRoleService;
import com.clavaris.organization.application.usecase.createworkspacerole.CreateWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.createworkspaceteam.CreateWorkspaceTeamService;
import com.clavaris.organization.application.usecase.createworkspaceteam.CreateWorkspaceTeamUseCase;
import com.clavaris.organization.application.usecase.createworkspaceteam.WorkspaceTeamRepository;
import com.clavaris.organization.application.usecase.deleteorganization.EventOutboxWriter;
import com.clavaris.organization.application.usecase.deleteworkspace.DeleteWorkspaceService;
import com.clavaris.organization.application.usecase.deleteworkspace.DeleteWorkspaceUseCase;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleService;
import com.clavaris.organization.application.usecase.deleteworkspacerole.DeleteWorkspaceRoleUseCase;
import com.clavaris.organization.application.usecase.deleteworkspaceteam.DeleteWorkspaceTeamService;
import com.clavaris.organization.application.usecase.deleteworkspaceteam.DeleteWorkspaceTeamUseCase;
import com.clavaris.organization.application.usecase.findworkspacemembershipforaccount.FindWorkspaceMembershipForAccountService;
import com.clavaris.organization.application.usecase.findworkspacemembershipforaccount.FindWorkspaceMembershipForAccountUseCase;
import com.clavaris.organization.application.usecase.getworkspacefororganization.GetWorkspaceForOrganizationService;
import com.clavaris.organization.application.usecase.getworkspacefororganization.GetWorkspaceForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspacemembers.ListWorkspaceMembersService;
import com.clavaris.organization.application.usecase.listworkspacemembers.ListWorkspaceMembersUseCase;
import com.clavaris.organization.application.usecase.listworkspacememberspaged.ListWorkspaceMembersPagedService;
import com.clavaris.organization.application.usecase.listworkspacememberspaged.ListWorkspaceMembersPagedUseCase;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationService;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspacesfororganization.ListWorkspacesForOrganizationService;
import com.clavaris.organization.application.usecase.listworkspacesfororganization.ListWorkspacesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged.ListWorkspacesForOrganizationPagedService;
import com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged.ListWorkspacesForOrganizationPagedUseCase;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListGroupedWorkspaceRoleIdsService;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListGroupedWorkspaceRoleIdsUseCase;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamRoleIdsForTeamsService;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamRoleIdsForTeamsUseCase;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamRoleIdsService;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamRoleIdsUseCase;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamsForWorkspaceService;
import com.clavaris.organization.application.usecase.listworkspaceteamsforworkspace.ListWorkspaceTeamsForWorkspaceUseCase;
import com.clavaris.organization.application.usecase.removerolefromworkspaceteam.RemoveRoleFromWorkspaceTeamService;
import com.clavaris.organization.application.usecase.removerolefromworkspaceteam.RemoveRoleFromWorkspaceTeamUseCase;
import com.clavaris.organization.application.usecase.removeworkspacemember.RemoveWorkspaceMemberService;
import com.clavaris.organization.application.usecase.removeworkspacemember.RemoveWorkspaceMemberUseCase;
import com.clavaris.organization.application.usecase.removeworkspacemember.WorkspaceMemberAccountRevoker;
import com.clavaris.organization.application.usecase.removeworkspacemember.WorkspaceMemberRefreshTokenRevoker;
import com.clavaris.organization.application.usecase.renameworkspaceteam.RenameWorkspaceTeamService;
import com.clavaris.organization.application.usecase.renameworkspaceteam.RenameWorkspaceTeamUseCase;
import com.clavaris.organization.application.usecase.updateworkspacerole.UpdateWorkspaceRoleService;
import com.clavaris.organization.application.usecase.updateworkspacerole.UpdateWorkspaceRoleUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Wires the Workspace use cases (workspaces, members, roles, teams, listings) to Spring's context.
 * Split from {@code OrganizationUseCaseConfig} (TD-ARCH-042). Named with a module prefix, not plain
 * {@code UseCaseConfig}: confirmed live (client-registry-module's own {@code
 * ClientRegistryUseCaseConfig}) that the default class-name-derived bean name collides the moment
 * two modules sharing that plain name are on the same classpath.
 */
// Literals: the repeated string is "PMD.LongVariable" itself, reused across several @Bean
// method parameters that legitimately share the same port name — same false positive
// identity-module's own IdentityUseCaseConfig class-level suppression already documents.
// ExcessiveImports/CouplingBetweenObjects/TooManyMethods: this class's whole job is wiring one
// @Bean method per use case (this file's own doc comment) — the Workspace feature, then ADR-0022's
// social-credential use cases, each added more, tipping method/import/collaborator counts over
// PMD's default thresholds. Same "wiring, not sprawl" reasoning
// OrganizationAuthorizationServerConfig's
// own class-level Javadoc already documents for an identical situation, not worth splitting a class
// whose entire job is Spring bean assembly.
@SuppressWarnings({"PMD.ExcessiveImports", "PMD.CouplingBetweenObjects", "PMD.TooManyMethods"})
@Configuration
class WorkspaceUseCaseConfig {

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  /* package */ WorkspaceUseCaseConfig() {
    // Intentionally empty — this class holds no state, only the @Bean method below.
  }

  @Bean
  /* package */ CreateWorkspaceUseCase createWorkspaceUseCase(
      final WorkspaceRepository workspaces,
      final WorkspaceRoleRepository roles,
      final OrganizationRepository organizations,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter eventOutboxWriter) {
    return new CreateWorkspaceService(
        workspaces, roles, organizations, auditEvents, eventOutboxWriter);
  }

  @Bean
  /* package */ DeleteWorkspaceUseCase deleteWorkspaceUseCase(
      final WorkspaceRepository workspaces,
      final WorkspaceMembershipRepository memberships,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter eventOutboxWriter,
      // TD-WS-004 (closed): see DeleteWorkspaceService's own Javadoc.
      final WorkspaceMemberRefreshTokenRevoker refreshTokenRevoker,
      final WorkspaceMemberAccountRevoker accountRevoker) {
    return new DeleteWorkspaceService(
        workspaces,
        memberships,
        auditEvents,
        eventOutboxWriter,
        refreshTokenRevoker,
        accountRevoker);
  }

  // AddWorkspaceMemberService's own Javadoc explains why this needs a real TransactionTemplate,
  // not @Transactional on the service method itself: it calls AccountProvisioner (a real
  // cross-module write + network mail send) between its own read and its own transactional write,
  // and @Transactional on the whole method would hold a DB transaction open across that call.
  @Bean
  /* package */ AddWorkspaceMemberUseCase addWorkspaceMemberUseCase(
      final WorkspaceRepository workspaces,
      final WorkspaceRoleRepository roles,
      final WorkspaceMembershipRepository memberships,
      final AccountProvisioner accountProvisioner,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter eventOutboxWriter,
      final PlatformTransactionManager transactionManager) {
    return new AddWorkspaceMemberService(
        workspaces,
        roles,
        memberships,
        accountProvisioner,
        auditEvents,
        eventOutboxWriter,
        new TransactionTemplate(transactionManager));
  }

  @Bean
  /* package */ ChangeWorkspaceMemberRoleUseCase changeWorkspaceMemberRoleUseCase(
      final WorkspaceMembershipRepository memberships,
      final WorkspaceRepository workspaces,
      final WorkspaceRoleRepository roles,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter eventOutboxWriter) {
    return new ChangeWorkspaceMemberRoleService(
        memberships, workspaces, roles, auditEvents, eventOutboxWriter);
  }

  @Bean
  /* package */ AssignWorkspaceRoleToAccountUseCase assignWorkspaceRoleToAccountUseCase(
      final WorkspaceRepository workspaces,
      final WorkspaceRoleRepository roles,
      final WorkspaceMembershipRepository memberships,
      final OrganizationAccountDirectory accountDirectory,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter eventOutboxWriter) {
    return new AssignWorkspaceRoleToAccountService(
        workspaces, roles, memberships, accountDirectory, auditEvents, eventOutboxWriter);
  }

  @Bean
  /* package */ FindWorkspaceMembershipForAccountUseCase findWorkspaceMembershipForAccountUseCase(
      final WorkspaceMembershipRepository memberships) {
    return new FindWorkspaceMembershipForAccountService(memberships);
  }

  @Bean
  /* package */ RemoveWorkspaceMemberUseCase removeWorkspaceMemberUseCase(
      final WorkspaceMembershipRepository memberships,
      final WorkspaceRepository workspaces,
      final WorkspaceRoleRepository roles,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter eventOutboxWriter,
      // TD-WS-002 (closed): see WorkspaceMemberRefreshTokenRevoker/WorkspaceMemberAccountRevoker's
      // own Javadoc.
      final WorkspaceMemberRefreshTokenRevoker refreshTokenRevoker,
      final WorkspaceMemberAccountRevoker accountRevoker) {
    return new RemoveWorkspaceMemberService(
        memberships,
        workspaces,
        roles,
        auditEvents,
        eventOutboxWriter,
        refreshTokenRevoker,
        accountRevoker);
  }

  @Bean
  /* package */ ListWorkspacesForOrganizationUseCase listWorkspacesForOrganizationUseCase(
      final WorkspaceRepository workspaces) {
    return new ListWorkspacesForOrganizationService(workspaces);
  }

  @Bean
  /* package */ ListWorkspaceRolesForOrganizationUseCase listWorkspaceRolesForOrganizationUseCase(
      final WorkspaceRoleRepository roles) {
    return new ListWorkspaceRolesForOrganizationService(roles);
  }

  @Bean
  /* package */ CreateWorkspaceRoleUseCase createWorkspaceRoleUseCase(
      final WorkspaceRoleRepository roles,
      final OrganizationRepository organizations,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter eventOutboxWriter) {
    return new CreateWorkspaceRoleService(roles, organizations, auditEvents, eventOutboxWriter);
  }

  @Bean
  /* package */ UpdateWorkspaceRoleUseCase updateWorkspaceRoleUseCase(
      final WorkspaceRoleRepository roles,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter eventOutboxWriter) {
    return new UpdateWorkspaceRoleService(roles, auditEvents, eventOutboxWriter);
  }

  @Bean
  /* package */ DeleteWorkspaceRoleUseCase deleteWorkspaceRoleUseCase(
      final WorkspaceRoleRepository roles,
      final WorkspaceMembershipRepository memberships,
      final AuditEventRecorder auditEvents,
      final EventOutboxWriter eventOutboxWriter) {
    return new DeleteWorkspaceRoleService(roles, memberships, auditEvents, eventOutboxWriter);
  }

  // ADR-0028: WorkspaceTeam CRUD + role-association use cases.
  @Bean
  /* package */ CreateWorkspaceTeamUseCase createWorkspaceTeamUseCase(
      final WorkspaceTeamRepository teams,
      final WorkspaceRepository workspaces,
      final AuditEventRecorder auditEvents) {
    return new CreateWorkspaceTeamService(teams, workspaces, auditEvents);
  }

  @Bean
  /* package */ RenameWorkspaceTeamUseCase renameWorkspaceTeamUseCase(
      final WorkspaceTeamRepository teams, final AuditEventRecorder auditEvents) {
    return new RenameWorkspaceTeamService(teams, auditEvents);
  }

  @Bean
  /* package */ DeleteWorkspaceTeamUseCase deleteWorkspaceTeamUseCase(
      final WorkspaceTeamRepository teams,
      final DeleteWorkspaceRoleUseCase deleteWorkspaceRoleUseCase,
      final AuditEventRecorder auditEvents,
      final WorkspaceMembershipRepository memberships,
      final WorkspaceRoleRepository roles) {
    return new DeleteWorkspaceTeamService(
        teams, deleteWorkspaceRoleUseCase, auditEvents, memberships, roles);
  }

  @Bean
  /* package */ AddRoleToWorkspaceTeamUseCase addRoleToWorkspaceTeamUseCase(
      final WorkspaceTeamRepository teams,
      final WorkspaceRepository workspaces,
      final WorkspaceRoleRepository roles,
      final AuditEventRecorder auditEvents) {
    return new AddRoleToWorkspaceTeamService(teams, workspaces, roles, auditEvents);
  }

  @Bean
  /* package */ RemoveRoleFromWorkspaceTeamUseCase removeRoleFromWorkspaceTeamUseCase(
      final WorkspaceTeamRepository teams, final AuditEventRecorder auditEvents) {
    return new RemoveRoleFromWorkspaceTeamService(teams, auditEvents);
  }

  @Bean
  /* package */ ListWorkspaceTeamsForWorkspaceUseCase listWorkspaceTeamsForWorkspaceUseCase(
      final WorkspaceTeamRepository teams) {
    return new ListWorkspaceTeamsForWorkspaceService(teams);
  }

  @Bean
  /* package */ ListWorkspaceTeamRoleIdsUseCase listWorkspaceTeamRoleIdsUseCase(
      final WorkspaceTeamRepository teams) {
    return new ListWorkspaceTeamRoleIdsService(teams);
  }

  @Bean
  /* package */ ListWorkspaceTeamRoleIdsForTeamsUseCase listWorkspaceTeamRoleIdsForTeamsUseCase(
      final WorkspaceTeamRepository teams) {
    return new ListWorkspaceTeamRoleIdsForTeamsService(teams);
  }

  @Bean
  /* package */ ListGroupedWorkspaceRoleIdsUseCase listGroupedWorkspaceRoleIdsUseCase(
      final WorkspaceTeamRepository teams) {
    return new ListGroupedWorkspaceRoleIdsService(teams);
  }

  // TD-PERF-020: the dashboard's own paginated sibling — see that use case's own Javadoc.
  @Bean
  /* package */ ListWorkspacesForOrganizationPagedUseCase listWorkspacesForOrganizationPagedUseCase(
      final WorkspaceRepository workspaces) {
    return new ListWorkspacesForOrganizationPagedService(workspaces);
  }

  @Bean
  /* package */ ListWorkspaceMembersUseCase listWorkspaceMembersUseCase(
      final WorkspaceMembershipRepository memberships) {
    return new ListWorkspaceMembersService(memberships);
  }

  // TD-PERF-020: the dashboard's own paginated sibling — see that use case's own Javadoc.
  @Bean
  /* package */ ListWorkspaceMembersPagedUseCase listWorkspaceMembersPagedUseCase(
      final WorkspaceMembershipRepository memberships) {
    return new ListWorkspaceMembersPagedService(memberships);
  }

  // ADR-0025: the dashboard's own Workspace-detail-page ownership check — confirms a workspaceId
  // named in a /platform/dashboard/organizations/{organizationId}/workspaces/{workspaceId} URL
  // actually belongs to that same, already-ownership-verified organizationId.
  @Bean
  /* package */ GetWorkspaceForOrganizationUseCase getWorkspaceForOrganizationUseCase(
      final WorkspaceRepository workspaces) {
    return new GetWorkspaceForOrganizationService(workspaces);
  }
}
