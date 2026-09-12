package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountQuery;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.listworkspacesfororganization.ListWorkspacesForOrganizationUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.Workspace;
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
 * PlatformOrganizationDashboardControllerTest} — real template rendering, not a mocked view
 * resolver.
 */
class PlatformOrganizationDetailControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();

  private GetOrganizationForPlatformAccountUseCase getOrganization;
  private ListWorkspacesForOrganizationUseCase listWorkspaces;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    getOrganization = mock(GetOrganizationForPlatformAccountUseCase.class);
    listWorkspaces = mock(ListWorkspacesForOrganizationUseCase.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);
    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(listWorkspaces.handle(any())).thenReturn(List.of());

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
                new PlatformOrganizationDetailController(
                    getOrganization, listWorkspaces, currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void showsTheOrganizationAndItsWorkspacesWhenOwnedByTheCurrentAccount() throws Exception {
    Organization organization = Organization.register("Acme Co", OWNER_ID);
    Workspace workspace = Workspace.register(organization.id(), "Engineering");
    when(getOrganization.handle(any())).thenReturn(Optional.of(organization));
    when(listWorkspaces.handle(any())).thenReturn(List.of(workspace));

    mockMvc
        .perform(get("/platform/dashboard/organizations/{organizationId}", organization.id()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/organization-detail"))
        .andExpect(model().attribute("organization", organization))
        .andExpect(model().attribute("workspaces", List.of(workspace)));
  }

  @Test
  void resolvesTheOrganizationThroughTheOwnershipCheckingUseCase() throws Exception {
    UUID organizationId = UUID.randomUUID();
    Organization organization = Organization.register("Acme Co", OWNER_ID);
    when(getOrganization.handle(any())).thenReturn(Optional.of(organization));

    mockMvc.perform(get("/platform/dashboard/organizations/{organizationId}", organizationId));

    // Same anti-enumeration posture as RedirectUrlResolverBridge/ClientBrandingProviderBridge:
    // ownership is checked inside the use case, never bypassed by this controller.
    verify(getOrganization)
        .handle(new GetOrganizationForPlatformAccountQuery(organizationId, OWNER_ID));
  }

  // Deliberately one test, not two: an unknown organizationId and one owned by a different
  // PlatformAccount both resolve to the exact same Optional.empty() from the use case, so a
  // separate "different owner" test body would just be this same test copy-pasted — the use
  // case itself is what enforces ownership (its own dedicated unit test covers that distinction);
  // this test only proves the controller surfaces the use case's "not found" result as a real
  // 404 either way, never leaking a distinguishable 403 for a not-mine-but-real organization.
  @Test
  void returnsNotFoundWhenTheOrganizationIsUnknownOrNotOwnedByTheCurrentAccount() throws Exception {
    when(getOrganization.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/platform/dashboard/organizations/{organizationId}", UUID.randomUUID()))
        .andExpect(status().isNotFound());
  }
}
