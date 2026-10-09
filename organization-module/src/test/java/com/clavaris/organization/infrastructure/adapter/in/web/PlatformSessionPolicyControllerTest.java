package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
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
import com.clavaris.organization.application.usecase.getsessionpolicyfororganization.GetSessionPolicyForOrganizationUseCase;
import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.SetSessionPolicyForOrganizationCommand;
import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.SetSessionPolicyForOrganizationResult;
import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.SetSessionPolicyForOrganizationUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.SessionPolicy;
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
 * Same standalone MockMvc + real Thymeleaf setup as {@code PlatformRateLimitPolicyControllerTest}.
 */
class PlatformSessionPolicyControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();

  private GetOrganizationForPlatformAccountUseCase getOrganization;
  private SetSessionPolicyForOrganizationUseCase setSessionPolicy;
  private GetSessionPolicyForOrganizationUseCase getSessionPolicy;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private Organization organization;

  @BeforeEach
  void setUp() {
    getOrganization = mock(GetOrganizationForPlatformAccountUseCase.class);
    setSessionPolicy = mock(SetSessionPolicyForOrganizationUseCase.class);
    getSessionPolicy = mock(GetSessionPolicyForOrganizationUseCase.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organization = Organization.register("Acme Co", OWNER_ID);

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(getOrganization.handle(any())).thenReturn(Optional.of(organization));
    when(getSessionPolicy.handle(any())).thenReturn(SessionPolicy.defaults(organization.id()));

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
                new PlatformSessionPolicyController(
                    getOrganization, setSessionPolicy, getSessionPolicy, currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private String path() {
    return "/platform/dashboard/organizations/" + organization.id() + "/session-policy";
  }

  @Test
  void getShowsTheOrganizationsEffectiveSessionPolicy() throws Exception {
    SessionPolicy customized = SessionPolicy.define(organization.id(), 20_160, 1_440, 5, false);
    when(getSessionPolicy.handle(any())).thenReturn(customized);

    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/organization-session-policy"))
        .andExpect(model().attribute("sessionPolicy", customized))
        .andExpect(model().attribute("organizationName", "Acme Co"));
  }

  @Test
  void getReturnsNotFoundWhenTheOrganizationIsNotOwnedByTheCurrentAccount() throws Exception {
    when(getOrganization.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(get(path())).andExpect(status().isNotFound());
  }

  @Test
  void plainPostRedirectsAfterUpdatingThePolicy() throws Exception {
    SessionPolicy updated = SessionPolicy.define(organization.id(), 20_160, 1_440, 5, false);
    when(setSessionPolicy.handle(any()))
        .thenReturn(new SetSessionPolicyForOrganizationResult(updated));

    mockMvc
        .perform(
            post(path())
                .param("maximumLifetimeMinutes", "20160")
                .param("inactivityTimeoutMinutes", "1440")
                .param("reverificationWindowMinutes", "5")
                .param("multiSessionHandlingEnabled", "false"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(path()));

    verify(setSessionPolicy)
        .handle(
            new SetSessionPolicyForOrganizationCommand(
                organization.id(), 20_160, 1_440, 5, false, AuditActor.platformAccount(OWNER_ID)));
  }

  @Test
  void htmxPostReturnsTheSessionPolicyFragment() throws Exception {
    SessionPolicy updated = SessionPolicy.define(organization.id(), 20_160, 1_440, 5, false);
    when(setSessionPolicy.handle(any()))
        .thenReturn(new SetSessionPolicyForOrganizationResult(updated));

    mockMvc
        .perform(
            post(path())
                .param("maximumLifetimeMinutes", "20160")
                .param("inactivityTimeoutMinutes", "1440")
                .param("reverificationWindowMinutes", "5")
                .param("multiSessionHandlingEnabled", "false")
                .header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(
            view().name("organization/platform/organization-session-policy :: sessionPolicy"));
  }

  @Test
  void anOutOfRangeValueReRendersWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post(path())
                .param("maximumLifetimeMinutes", "4")
                .param("inactivityTimeoutMinutes", "1440")
                .param("reverificationWindowMinutes", "5")
                .param("multiSessionHandlingEnabled", "false"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/organization-session-policy"));

    verify(setSessionPolicy, never()).handle(any());
  }

  @Test
  void postReturnsNotFoundWhenTheOrganizationIsNotOwnedByTheCurrentAccount() throws Exception {
    when(getOrganization.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(
            post(path())
                .param("maximumLifetimeMinutes", "20160")
                .param("inactivityTimeoutMinutes", "1440")
                .param("reverificationWindowMinutes", "5")
                .param("multiSessionHandlingEnabled", "false"))
        .andExpect(status().isNotFound());

    verify(setSessionPolicy, never()).handle(any());
  }
}
