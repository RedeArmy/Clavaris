package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.application.usecase.authenticatewithpassword.AuthenticateWithPasswordCommand;
import com.clavaris.identity.application.usecase.authenticatewithpassword.AuthenticateWithPasswordUseCase;
import com.clavaris.identity.application.usecase.authenticatewithpassword.InvalidCredentialsException;
import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.OrganizationSocialLoginPolicyProvider;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialProvider;
import java.util.EnumSet;
import java.util.List;
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
 * Same standalone MockMvc + real Thymeleaf setup as {@link RegisterAccountControllerTest} — see its
 * Javadoc for why.
 */
class LoginControllerTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  private AuthenticateWithPasswordUseCase useCase;
  private AuthenticatedSessionEstablisher sessionEstablisher;
  private OrganizationSocialLoginPolicyProvider policyProvider;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(AuthenticateWithPasswordUseCase.class);
    sessionEstablisher = mock(AuthenticatedSessionEstablisher.class);
    policyProvider = mock(OrganizationSocialLoginPolicyProvider.class);

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
                new LoginController(useCase, sessionEstablisher, policyProvider))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void getShowsTheLoginFormWithNoSocialButtonsWhenNoneAreEnabled() throws Exception {
    mockMvc
        .perform(get("/o/{organizationId}/login", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/login"))
        .andExpect(model().attributeExists("form"))
        .andExpect(model().attribute("socialProviders", List.of()));
  }

  @Test
  void getShowsOnlyTheProvidersTheOrganizationHasEnabled() throws Exception {
    // Code review finding (TD-SEC-032, closed): the controller now calls allowedProviders() once
    // per render, not isProviderAllowed() once per known SocialProvider.
    when(policyProvider.allowedProviders(new OrganizationId(ORGANIZATION_ID)))
        .thenReturn(EnumSet.of(SocialProvider.GOOGLE));

    mockMvc
        .perform(get("/o/{organizationId}/login", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(model().attribute("socialProviders", List.of(SocialProvider.GOOGLE)));
  }

  @Test
  void validCredentialsEstablishASessionAndRedirectToWhatItReturns() throws Exception {
    AccountId accountId = AccountId.newId();
    when(useCase.handle(any())).thenReturn(accountId);
    when(sessionEstablisher.establish(any(), any(), eq(accountId.value()), anyString()))
        .thenReturn("/o/" + ORGANIZATION_ID + "/oauth2/authorize?client_id=abc");

    mockMvc
        .perform(
            post("/o/{organizationId}/login", ORGANIZATION_ID)
                .param("email", "user@example.com")
                .param("password", "correct-password"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/oauth2/authorize?client_id=abc"));

    verify(useCase)
        .handle(
            new AuthenticateWithPasswordCommand(
                new OrganizationId(ORGANIZATION_ID),
                new Email("user@example.com"),
                "correct-password"));
  }

  @Test
  void invalidEmailRerendersTheFormWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/login", ORGANIZATION_ID)
                .param("email", "not-an-email")
                .param("password", "some-password"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/login"))
        .andExpect(model().attributeHasFieldErrors("form", "email"));

    verifyNoInteractions(useCase);
    verifyNoInteractions(sessionEstablisher);
  }

  @Test
  void blankPasswordRerendersTheFormWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/login", ORGANIZATION_ID)
                .param("email", "user@example.com")
                .param("password", ""))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/login"))
        .andExpect(model().attributeHasFieldErrors("form", "password"));

    verifyNoInteractions(useCase);
  }

  @Test
  void wrongCredentialsRerenderTheFormWithAGenericErrorNeverAFieldError() throws Exception {
    when(useCase.handle(any())).thenThrow(new InvalidCredentialsException());

    mockMvc
        .perform(
            post("/o/{organizationId}/login", ORGANIZATION_ID)
                .param("email", "user@example.com")
                .param("password", "wrong-password"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/login"))
        .andExpect(model().attribute("loginError", true))
        // Deliberately never a field-level error — see LoginController's own comment on why a
        // field-scoped error would itself leak which field was the actual problem.
        .andExpect(model().attributeHasNoErrors("form"));

    verify(sessionEstablisher, never()).establish(any(), any(), any(), anyString());
  }
}
