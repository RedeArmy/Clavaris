package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.getratelimitpolicyfororganization.GetRateLimitPolicyForOrganizationUseCase;
import com.clavaris.organization.application.usecase.getratelimitpolicyfororganization.RateLimitPolicySnapshot;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.SetRateLimitPolicyForOrganizationCommand;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.SetRateLimitPolicyForOrganizationResult;
import com.clavaris.organization.application.usecase.setratelimitpolicyfororganization.SetRateLimitPolicyForOrganizationUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.RateLimitPolicy;
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
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private Organization organization;

  @BeforeEach
  void setUp() {
    getOrganization = mock(GetOrganizationForPlatformAccountUseCase.class);
    setRateLimitPolicy = mock(SetRateLimitPolicyForOrganizationUseCase.class);
    getRateLimitPolicy = mock(GetRateLimitPolicyForOrganizationUseCase.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organization = Organization.register("Acme Co", OWNER_ID);

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(getOrganization.handle(any())).thenReturn(Optional.of(organization));
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
                    currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private String path() {
    return "/platform/dashboard/organizations/" + organization.id() + "/rate-limit-policy";
  }

  @Test
  void getShowsTheOrganizationsEffectiveRateLimitPolicy() throws Exception {
    RateLimitPolicySnapshot customized =
        new RateLimitPolicySnapshot(1200, true, java.time.Instant.now(), HARD_CAP);
    when(getRateLimitPolicy.handle(any())).thenReturn(customized);

    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/organization-rate-limit"))
        .andExpect(model().attribute("rateLimitPolicy", customized))
        .andExpect(model().attribute("organizationName", "Acme Co"));
  }

  @Test
  void getReturnsNotFoundWhenTheOrganizationIsNotOwnedByTheCurrentAccount() throws Exception {
    when(getOrganization.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(get(path())).andExpect(status().isNotFound());
  }

  @Test
  void plainPostRedirectsAfterUpdatingTheCeiling() throws Exception {
    RateLimitPolicy updated = RateLimitPolicy.define(organization.id(), 1500, HARD_CAP);
    when(setRateLimitPolicy.handle(any()))
        .thenReturn(new SetRateLimitPolicyForOrganizationResult(updated));

    mockMvc
        .perform(post(path()).param("requestsPerMinute", "1500"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(path()));

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
        .andExpect(view().name("organization/platform/organization-rate-limit :: rateLimit"));
  }

  @Test
  void aNonPositiveValueReRendersWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(post(path()).param("requestsPerMinute", "0"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/organization-rate-limit"));

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
        .andExpect(view().name("organization/platform/organization-rate-limit"))
        .andExpect(model().attribute("hardCapExceededError", true));
  }

  @Test
  void postReturnsNotFoundWhenTheOrganizationIsNotOwnedByTheCurrentAccount() throws Exception {
    when(getOrganization.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(post(path()).param("requestsPerMinute", "1500"))
        .andExpect(status().isNotFound());

    verify(setRateLimitPolicy, never()).handle(any());
  }
}
