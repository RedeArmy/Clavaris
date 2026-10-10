package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.application.usecase.requestpasswordreset.RequestPasswordResetCommand;
import com.clavaris.identity.application.usecase.requestpasswordreset.RequestPasswordResetUseCase;
import com.clavaris.identity.application.usecase.resolveclientbranding.ClientBrandingProvider;
import com.clavaris.identity.application.usecase.resolveclientbranding.ClientBrandingSnapshot;
import com.clavaris.identity.application.usecase.resolveclienthomeurl.ClientHomeUrlResolver;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
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

/** Same standalone MockMvc setup rationale as {@code RegisterAccountControllerTest}. */
class ForgotPasswordControllerTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();

  private RequestPasswordResetUseCase useCase;
  private ClientHomeUrlResolver homeUrls;
  private ClientBrandingProvider branding;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(RequestPasswordResetUseCase.class);
    homeUrls = mock(ClientHomeUrlResolver.class);
    branding = mock(ClientBrandingProvider.class);
    when(branding.brandingFor(any(), any())).thenReturn(ClientBrandingSnapshot.unconfigured());

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
    // The tab title has an em dash; MockMvc would otherwise decode the page as Latin-1.
    viewResolver.setCharacterEncoding("UTF-8");

    mockMvc =
        MockMvcBuilders.standaloneSetup(
                new ForgotPasswordController(useCase, new ReturnToApplicationLink(homeUrls)))
            .addInterceptors(new ConsumerBrandNameInterceptor(branding))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void getShowsTheForm() throws Exception {
    mockMvc
        .perform(get("/o/{organizationId}/forgot-password", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/forgot-password"))
        .andExpect(model().attributeExists("form"));
  }

  @Test
  void validSubmissionAlwaysRedirectsToThePendingPage() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/forgot-password", ORGANIZATION_ID)
                .param("email", "someone@example.com"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/forgot-password/pending"));

    verify(useCase)
        .handle(
            new RequestPasswordResetCommand(
                new OrganizationId(ORGANIZATION_ID), new Email("someone@example.com")));
  }

  @Test
  void invalidEmailRerendersTheFormWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/forgot-password", ORGANIZATION_ID)
                .param("email", "not-an-email"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/forgot-password"))
        .andExpect(model().attributeHasFieldErrors("form", "email"));

    verifyNoInteractions(useCase);
  }

  @Test
  void pendingPageRenders() throws Exception {
    mockMvc
        .perform(get("/o/{organizationId}/forgot-password/pending", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/forgot-password-pending"));
  }

  @Test
  void theClientRidesTheFormAndTheRedirectToThePendingPage() throws Exception {
    mockMvc
        .perform(
            get("/o/{organizationId}/forgot-password", ORGANIZATION_ID)
                .param("clientId", "acme-web"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("/forgot-password?clientId=acme-web")));

    mockMvc
        .perform(
            post("/o/{organizationId}/forgot-password", ORGANIZATION_ID)
                .param("clientId", "acme-web")
                .param("email", "someone@example.com"))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            redirectedUrl("/o/" + ORGANIZATION_ID + "/forgot-password/pending?clientId=acme-web"));
  }

  @Test
  void pendingPageOffersABackToHomeLinkWhenTheClientHasAHome() throws Exception {
    when(homeUrls.resolve(new OrganizationId(ORGANIZATION_ID), "acme-web"))
        .thenReturn(Optional.of("https://app.acme.test/"));

    mockMvc
        .perform(
            get("/o/{organizationId}/forgot-password/pending", ORGANIZATION_ID)
                .param("clientId", "acme-web"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("href=\"https://app.acme.test/\"")))
        .andExpect(content().string(containsString("Back to home")));
  }

  @Test
  void pendingPageHasNoBackToHomeLinkWithoutAClientHome() throws Exception {
    mockMvc
        .perform(get("/o/{organizationId}/forgot-password/pending", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(model().attributeDoesNotExist("homeUrl"))
        .andExpect(content().string(not(containsString("Back to home"))));
  }

  // The standard for the consumer system: the tab carries the application's name, and its
  // Organization's when it has none of its own. Never Clavaris.
  @Test
  void theTabCarriesTheBrandNameAndNeverClavaris() throws Exception {
    when(branding.brandingFor(new OrganizationId(ORGANIZATION_ID), null))
        .thenReturn(
            new ClientBrandingSnapshot(
                Optional.empty(), Optional.empty(), Optional.of("Acme Analytics")));

    mockMvc
        .perform(get("/o/{organizationId}/forgot-password", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(
            content().string(containsString("<title>Acme Analytics — Forgot password</title>")))
        .andExpect(content().string(not(containsString("Clavaris"))));
  }

  @Test
  void theTabIsJustThePageTitleWhenEvenTheOrganizationHasNoName() throws Exception {
    mockMvc
        .perform(get("/o/{organizationId}/forgot-password", ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("<title>Forgot password</title>")))
        .andExpect(content().string(not(containsString("Clavaris"))));
  }

  @Test
  void thePendingPageCarriesTheBrandNameToo() throws Exception {
    when(branding.brandingFor(new OrganizationId(ORGANIZATION_ID), "acme-web"))
        .thenReturn(
            new ClientBrandingSnapshot(
                Optional.empty(), Optional.empty(), Optional.of("Acme Dashboard")));

    mockMvc
        .perform(
            get("/o/{organizationId}/forgot-password/pending", ORGANIZATION_ID)
                .param("clientId", "acme-web"))
        .andExpect(status().isOk())
        .andExpect(
            content().string(containsString("<title>Acme Dashboard — Check your email</title>")));
  }
}
