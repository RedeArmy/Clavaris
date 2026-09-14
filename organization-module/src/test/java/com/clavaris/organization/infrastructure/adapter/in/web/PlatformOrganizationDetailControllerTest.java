package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.common.domain.model.Page;
import com.clavaris.common.domain.model.PageRequest;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountQuery;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.getratelimitpolicyfororganization.GetRateLimitPolicyForOrganizationUseCase;
import com.clavaris.organization.application.usecase.getratelimitpolicyfororganization.RateLimitPolicySnapshot;
import com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged.ListWorkspacesForOrganizationPagedQuery;
import com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged.ListWorkspacesForOrganizationPagedUseCase;
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
  private ListWorkspacesForOrganizationPagedUseCase listWorkspaces;
  private GetRateLimitPolicyForOrganizationUseCase getRateLimitPolicy;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    getOrganization = mock(GetOrganizationForPlatformAccountUseCase.class);
    listWorkspaces = mock(ListWorkspacesForOrganizationPagedUseCase.class);
    getRateLimitPolicy = mock(GetRateLimitPolicyForOrganizationUseCase.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);
    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(listWorkspaces.handle(any())).thenReturn(emptyPage());
    when(getRateLimitPolicy.handle(any()))
        .thenReturn(new RateLimitPolicySnapshot(600, false, null));

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
                    getOrganization, listWorkspaces, getRateLimitPolicy, currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private static Page<Workspace> emptyPage() {
    return new Page<>(List.of(), 0, PageRequest.DEFAULT_SIZE, 0);
  }

  @Test
  void showsTheOrganizationAndItsWorkspacesWhenOwnedByTheCurrentAccount() throws Exception {
    Organization organization = Organization.register("Acme Co", OWNER_ID);
    Workspace workspace = Workspace.register(organization.id(), "Engineering");
    when(getOrganization.handle(any())).thenReturn(Optional.of(organization));
    when(listWorkspaces.handle(any()))
        .thenReturn(new Page<>(List.of(workspace), 0, PageRequest.DEFAULT_SIZE, 1));

    mockMvc
        .perform(get("/platform/dashboard/organizations/{organizationId}", organization.id()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/organization-detail"))
        .andExpect(model().attribute("organization", organization))
        .andExpect(model().attribute("workspaces", List.of(workspace)));
  }

  @Test
  void showsTheOrganizationsEffectiveRateLimitPolicy() throws Exception {
    Organization organization = Organization.register("Acme Co", OWNER_ID);
    RateLimitPolicySnapshot customized =
        new RateLimitPolicySnapshot(1200, true, java.time.Instant.now());
    when(getOrganization.handle(any())).thenReturn(Optional.of(organization));
    when(getRateLimitPolicy.handle(any())).thenReturn(customized);

    mockMvc
        .perform(get("/platform/dashboard/organizations/{organizationId}", organization.id()))
        .andExpect(status().isOk())
        .andExpect(model().attribute("rateLimitPolicy", customized));
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

  // TD-PERF-020: proves ?page= is actually threaded into the query.
  @Test
  void passesTheRequestedPageThroughToTheUseCase() throws Exception {
    Organization organization = Organization.register("Acme Co", OWNER_ID);
    when(getOrganization.handle(any())).thenReturn(Optional.of(organization));

    mockMvc.perform(
        get("/platform/dashboard/organizations/{organizationId}", organization.id())
            .param("page", "3"));

    verify(listWorkspaces)
        .handle(
            new ListWorkspacesForOrganizationPagedQuery(
                organization.id(), new PageRequest(3, PageRequest.DEFAULT_SIZE)));
  }

  // TD-PERF-020: an HTMX-originated pagination link (hx-get) must get back just the workspaces
  // fragment, not the full page — the fragment's own hx-target/hx-swap="outerHTML" would otherwise
  // splice a whole second HTML document into one div.
  @Test
  void htmxGetReturnsTheWorkspacesFragmentInsteadOfTheFullPage() throws Exception {
    Organization organization = Organization.register("Acme Co", OWNER_ID);
    when(getOrganization.handle(any())).thenReturn(Optional.of(organization));

    mockMvc
        .perform(
            get("/platform/dashboard/organizations/{organizationId}", organization.id())
                .header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/organization-detail :: workspaces"));
  }
}
