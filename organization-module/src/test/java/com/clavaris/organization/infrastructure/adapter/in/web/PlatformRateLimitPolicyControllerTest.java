package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.getratelimitpolicyfororganization.GetRateLimitPolicyForOrganizationUseCase;
import com.clavaris.organization.application.usecase.getratelimitpolicyfororganization.RateLimitPolicySnapshot;
import com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged.ListWorkspacesForOrganizationPagedUseCase;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.SetRateLimitPolicyForOrganizationCommand;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.SetRateLimitPolicyForOrganizationResult;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.SetRateLimitPolicyForOrganizationUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.RateLimitPolicy;
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
 * Same standalone MockMvc + real Thymeleaf setup as {@link PlatformWorkspaceControllerTest}. Two
 * ownership-boundary and hard-cap-enforcement scenarios are the point of this test class — the hard
 * cap value itself (whether {@code IllegalArgumentException} is actually thrown for an over-cap
 * value) is {@code RateLimitPolicy}'s own domain test's job, not re-proved here; this class proves
 * the controller reacts to that exception correctly.
 */
class PlatformRateLimitPolicyControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();
  private static final int HARD_CAP = 6000;

  private GetOrganizationForPlatformAccountUseCase getOrganization;
  private SetRateLimitPolicyForOrganizationUseCase setRateLimitPolicy;
  private GetRateLimitPolicyForOrganizationUseCase getRateLimitPolicy;
  private ListWorkspacesForOrganizationPagedUseCase listWorkspaces;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private Organization organization;

  @BeforeEach
  void setUp() {
    getOrganization = mock(GetOrganizationForPlatformAccountUseCase.class);
    setRateLimitPolicy = mock(SetRateLimitPolicyForOrganizationUseCase.class);
    getRateLimitPolicy = mock(GetRateLimitPolicyForOrganizationUseCase.class);
    listWorkspaces = mock(ListWorkspacesForOrganizationPagedUseCase.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organization = Organization.register("Acme Co", OWNER_ID);

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(getOrganization.handle(any())).thenReturn(Optional.of(organization));
    when(listWorkspaces.handle(any())).thenReturn(emptyWorkspacesPage());
    when(getRateLimitPolicy.handle(any()))
        .thenReturn(new RateLimitPolicySnapshot(600, false, null, HARD_CAP));

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
                new PlatformRateLimitPolicyController(
                    getOrganization,
                    setRateLimitPolicy,
                    getRateLimitPolicy,
                    listWorkspaces,
                    currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private static KeysetPage<Workspace> emptyWorkspacesPage() {
    return new KeysetPage<>(List.of(), null, null, false, false);
  }

  private String path() {
    return "/platform/dashboard/organizations/" + organization.id() + "/rate-limit-policy";
  }

  @Test
  void plainPostRedirectsAfterUpdatingTheCeiling() throws Exception {
    RateLimitPolicy updated = RateLimitPolicy.define(organization.id(), 1500, HARD_CAP);
    when(setRateLimitPolicy.handle(any()))
        .thenReturn(new SetRateLimitPolicyForOrganizationResult(updated));

    mockMvc
        .perform(post(path()).param("requestsPerMinute", "1500"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/platform/dashboard/organizations/" + organization.id()));

    verify(setRateLimitPolicy)
        .handle(
            new SetRateLimitPolicyForOrganizationCommand(
                organization.id(), 1500, AuditActor.platformAccount(OWNER_ID)));
  }

  @Test
  void htmxPostReturnsTheRateLimitFragment() throws Exception {
    RateLimitPolicy updated = RateLimitPolicy.define(organization.id(), 1500, HARD_CAP);
    when(setRateLimitPolicy.handle(any()))
        .thenReturn(new SetRateLimitPolicyForOrganizationResult(updated));

    mockMvc
        .perform(post(path()).param("requestsPerMinute", "1500").header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/organization-detail :: rateLimit"));
  }

  @Test
  void aNonPositiveValueReRendersWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(post(path()).param("requestsPerMinute", "0"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/organization-detail"));

    verify(setRateLimitPolicy, never()).handle(any());
  }

  // The hard-cap check itself lives in RateLimitPolicy's own factory/update methods (see that
  // class's own test) — this proves the controller turns the IllegalArgumentException those
  // methods throw into a re-rendered form with a real error, not a propagated 500.
  @Test
  void anOverCapValueRendersAHardCapErrorInsteadOfPropagatingTheException() throws Exception {
    doThrow(new IllegalArgumentException("requestsPerMinute (9000) must not exceed the hard cap"))
        .when(setRateLimitPolicy)
        .handle(any());

    mockMvc
        .perform(post(path()).param("requestsPerMinute", "9000"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/organization-detail"))
        .andExpect(model().attribute("hardCapExceededError", true));
  }

  @Test
  void returnsNotFoundWhenTheOrganizationIsNotOwnedByTheCurrentAccount() throws Exception {
    when(getOrganization.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(post(path()).param("requestsPerMinute", "1500"))
        .andExpect(status().isNotFound());

    verify(setRateLimitPolicy, never()).handle(any());
  }
}
