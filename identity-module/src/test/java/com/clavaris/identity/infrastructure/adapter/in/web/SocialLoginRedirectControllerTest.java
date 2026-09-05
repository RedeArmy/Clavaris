package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.application.usecase.authenticatewithsocialprovider.OrganizationSocialLoginPolicyProvider;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.SocialProvider;
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
 * Standalone MockMvc setup — real Thymeleaf wired in (same setup as {@code
 * VerifyEmailControllerTest}) since the two {@code confirmation-required} endpoints actually render
 * a view, unlike the redirect-only ones.
 */
class SocialLoginRedirectControllerTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  private OrganizationSocialLoginPolicyProvider policyProvider;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
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
        MockMvcBuilders.standaloneSetup(new SocialLoginRedirectController(policyProvider))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void redirectsToTheProviderAuthorizationEndpointWhenAllowed() throws Exception {
    when(policyProvider.isProviderAllowed(
            new OrganizationId(ORGANIZATION_ID), SocialProvider.GOOGLE))
        .thenReturn(true);

    mockMvc
        .perform(get("/o/{organizationId}/login/social/google", ORGANIZATION_ID))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/oauth2/authorization/google"));
  }

  // Clerk "customize redirect URLs" parity: stashed in the session so app's own
  // SocialLoginAuthenticationSuccessHandler can read them back once the provider redirects here —
  // see SocialLoginRedirectController's own Javadoc for why a session round trip is needed at all.
  @Test
  void stashesClientIdAndRedirectUrlInTheSessionWhenPresent() throws Exception {
    when(policyProvider.isProviderAllowed(
            new OrganizationId(ORGANIZATION_ID), SocialProvider.GOOGLE))
        .thenReturn(true);

    org.springframework.mock.web.MockHttpSession session =
        new org.springframework.mock.web.MockHttpSession();
    mockMvc
        .perform(
            get("/o/{organizationId}/login/social/google", ORGANIZATION_ID)
                .session(session)
                .param("clientId", "test_client")
                .param("redirectUrl", "https://app.example.com/callback"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/oauth2/authorization/google"));

    org.assertj.core.api.Assertions.assertThat(
            session.getAttribute(SocialLoginRedirectController.CLIENT_ID_SESSION_ATTRIBUTE))
        .isEqualTo("test_client");
    org.assertj.core.api.Assertions.assertThat(
            session.getAttribute(SocialLoginRedirectController.REDIRECT_URL_SESSION_ATTRIBUTE))
        .isEqualTo("https://app.example.com/callback");
  }

  @Test
  void redirectsBackToLoginWithAnErrorWhenTheOrganizationHasNotEnabledTheProvider()
      throws Exception {
    when(policyProvider.isProviderAllowed(
            new OrganizationId(ORGANIZATION_ID), SocialProvider.GITHUB))
        .thenReturn(false);

    mockMvc
        .perform(get("/o/{organizationId}/login/social/github", ORGANIZATION_ID))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/login?socialLoginError"));
  }

  @Test
  void redirectsBackToLoginWithAnErrorForAnUnknownProviderName() throws Exception {
    mockMvc
        .perform(get("/o/{organizationId}/login/social/facebook", ORGANIZATION_ID))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/login?socialLoginError"));
  }

  @Test
  void platformRedirectsToTheProviderAuthorizationEndpoint() throws Exception {
    mockMvc
        .perform(get("/platform/login/social/github"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/oauth2/authorization/github"));
  }

  @Test
  void platformRedirectsBackToLoginWithAnErrorForAnUnknownProviderName() throws Exception {
    mockMvc
        .perform(get("/platform/login/social/facebook"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/platform/login?socialLoginError"));
  }

  @Test
  void tenantConfirmationRequiredRendersItsOwnPage() throws Exception {
    mockMvc
        .perform(get("/o/{organizationId}/login/social/confirmation-required", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/social-login-confirmation-required"));
  }

  @Test
  void platformConfirmationRequiredRendersItsOwnPage() throws Exception {
    mockMvc
        .perform(get("/platform/login/social/confirmation-required"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/social-login-confirmation-required"));
  }
}
