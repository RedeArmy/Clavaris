package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.AuditEvent;
import com.clavaris.organization.application.usecase.getauditlogfororganization.GetAuditLogForOrganizationUseCase;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspacesfororganization.ListWorkspacesForOrganizationUseCase;
import com.clavaris.organization.domain.model.Organization;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/**
 * Same standalone MockMvc + real Thymeleaf setup as {@link
 * PlatformOrganizationDetailControllerTest}.
 */
class PlatformAuditLogControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();

  private GetOrganizationForPlatformAccountUseCase getOrganization;
  private GetAuditLogForOrganizationUseCase getAuditLog;
  private ListWorkspacesForOrganizationUseCase listWorkspaces;
  private ListWorkspaceRolesForOrganizationUseCase listRoles;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private Organization organization;

  @BeforeEach
  void setUp() {
    getOrganization = mock(GetOrganizationForPlatformAccountUseCase.class);
    getAuditLog = mock(GetAuditLogForOrganizationUseCase.class);
    listWorkspaces = mock(ListWorkspacesForOrganizationUseCase.class);
    listRoles = mock(ListWorkspaceRolesForOrganizationUseCase.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organization = Organization.register("Acme Co", OWNER_ID);

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(getOrganization.handle(any())).thenReturn(Optional.of(organization));
    when(getAuditLog.handle(any())).thenReturn(List.of());
    when(listWorkspaces.handle(any())).thenReturn(List.of());
    when(listRoles.handle(any())).thenReturn(List.of());

    GenericApplicationContext applicationContext = new GenericApplicationContext();
    applicationContext.refresh();

    SpringResourceTemplateResolver templateResolver = new SpringResourceTemplateResolver();
    templateResolver.setApplicationContext(applicationContext);
    templateResolver.setPrefix("classpath:/templates/");
    templateResolver.setSuffix(".html");

    SpringTemplateEngine templateEngine = new SpringTemplateEngine();
    templateEngine.setTemplateResolver(templateResolver);

    ThymeleafViewResolver viewResolver = new ThymeleafViewResolver();
    viewResolver.setTemplateEngine(templateEngine);

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new PlatformAuditLogController(
                    getOrganization,
                    getAuditLog,
                    listWorkspaces,
                    listRoles,
                    currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private String path() {
    return "/platform/dashboard/organizations/" + organization.id() + "/audit-log";
  }

  private AuditEvent deletedKeyBy(final AuditActor actor) {
    return AuditEvent.of(
        actor,
        "organization_client.deleted",
        "Organization",
        organization.id().toString(),
        "deletedClientId=sk_test_example0-0000-0000-0000-000000000000");
  }

  @Test
  void showsTheOrganizationsAuditLogAsPresenterRows() throws Exception {
    when(getAuditLog.handle(organization.id()))
        .thenReturn(List.of(deletedKeyBy(AuditActor.platformAccount(OWNER_ID))));

    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/audit-log"))
        .andExpect(model().attributeExists("entries", "categories"))
        .andExpect(model().attribute("totalCount", 1))
        .andExpect(model().attribute("selectedCategory", ""));
  }

  // The point of the page: a person reads a sentence and a name, not a machine key and a UUID.
  @Test
  void readsLikeAPersonWouldWriteItNotLikeTheRawRecord() throws Exception {
    when(getAuditLog.handle(organization.id()))
        .thenReturn(List.of(deletedKeyBy(AuditActor.platformAccount(OWNER_ID))));

    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Secret Key deleted")))
        .andExpect(content().string(containsString(">You<")))
        .andExpect(content().string(containsString(">sk_test_example0")))
        .andExpect(content().string(containsString("Technical details")))
        .andExpect(content().string(containsString("Audit log, newest first")));
  }

  @Test
  void theRawRecordIsOnlyEverInsideTheCollapsedTechnicalDetails() throws Exception {
    when(getAuditLog.handle(organization.id()))
        .thenReturn(List.of(deletedKeyBy(AuditActor.platformAccount(OWNER_ID))));

    final String html = mockMvc.perform(get(path())).andReturn().getResponse().getContentAsString();
    final String visible = html.substring(0, html.indexOf("<details"));

    org.junit.jupiter.api.Assertions.assertFalse(visible.contains("deletedClientId="));
    org.junit.jupiter.api.Assertions.assertFalse(
        visible.contains("PLATFORM_ACCOUNT:" + OWNER_ID + "<"));
    org.junit.jupiter.api.Assertions.assertTrue(html.contains("deletedClientId="));
  }

  @Test
  void categoryFilterNarrowsTheListAndTheChipsCountEachGroup() throws Exception {
    AuditEvent secretKey = deletedKeyBy(AuditActor.platformAccount(OWNER_ID));
    AuditEvent workspace =
        AuditEvent.of(
            AuditActor.platformAccount(OWNER_ID), "workspace.created", "Workspace", "ws-1", null);
    when(getAuditLog.handle(organization.id())).thenReturn(List.of(secretKey, workspace));

    mockMvc
        .perform(get(path()).param("category", "secret-keys"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("selectedCategory", "secret-keys"))
        .andExpect(model().attribute("totalCount", 2))
        .andExpect(content().string(containsString("Secret Key deleted")))
        .andExpect(content().string(not(containsString("Workspace created"))))
        .andExpect(content().string(containsString("Workspaces &amp; Roles")));
  }

  @Test
  void anUnknownCategoryShowsEverything() throws Exception {
    when(getAuditLog.handle(organization.id()))
        .thenReturn(List.of(deletedKeyBy(AuditActor.platformAccount(OWNER_ID))));

    mockMvc
        .perform(get(path()).param("category", "nonsense"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("selectedCategory", ""))
        .andExpect(content().string(containsString("Secret Key deleted")));
  }

  @Test
  void rolesAndWorkspacesStillExistingAreNamedInTheDetails() throws Exception {
    when(listWorkspaces.handle(any())).thenReturn(List.of());
    com.clavaris.organization.domain.model.WorkspaceRole recruiter =
        com.clavaris.organization.domain.model.WorkspaceRole.define(
            organization.id(), "Recruiter", null, java.util.Set.of());
    when(listRoles.handle(any())).thenReturn(List.of(recruiter));
    AuditEvent changed =
        AuditEvent.of(
            AuditActor.platformAccount(OWNER_ID),
            "workspace_membership.role_changed",
            "WorkspaceMembership",
            "m-1",
            "previousRoleId=gone-role newRoleId=" + recruiter.id());
    when(getAuditLog.handle(organization.id())).thenReturn(List.of(changed));

    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Member&#39;s role changed")))
        .andExpect(content().string(containsString(">Recruiter<")));
  }

  @Test
  void anEmptyLogSaysSoPlainly() throws Exception {
    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(
            content().string(containsString("Nothing has been changed in this Organization yet")));
  }

  @Test
  void returnsNotFoundWhenTheOrganizationIsUnknownOrNotOwnedByTheCurrentAccount() throws Exception {
    when(getOrganization.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(get(path())).andExpect(status().isNotFound());
  }
}
