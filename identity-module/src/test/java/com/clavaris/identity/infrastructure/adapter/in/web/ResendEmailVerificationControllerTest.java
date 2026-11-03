package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicyProvider;
import com.clavaris.identity.application.usecase.requestemailverification.AccountAuthenticationPolicySnapshot;
import com.clavaris.identity.application.usecase.requestemailverification.EmailVerificationMethod;
import com.clavaris.identity.application.usecase.resendemailverification.ResendEmailVerificationCommand;
import com.clavaris.identity.application.usecase.resendemailverification.ResendEmailVerificationUseCase;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/** Same standalone MockMvc + real Thymeleaf setup as {@code VerifyEmailCodeControllerTest}. */
class ResendEmailVerificationControllerTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();
  private static final OrganizationId ORG = new OrganizationId(ORGANIZATION_ID);

  private ResendEmailVerificationUseCase useCase;
  private AccountAuthenticationPolicyProvider policyProvider;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(ResendEmailVerificationUseCase.class);
    policyProvider = mock(AccountAuthenticationPolicyProvider.class);
    when(policyProvider.policyFor(ORG)).thenReturn(policy(EmailVerificationMethod.LINK, false));

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
                new ResendEmailVerificationController(useCase, policyProvider))
            .setViewResolvers(viewResolver)
            .build();
  }

  private static AccountAuthenticationPolicySnapshot policy(
      final EmailVerificationMethod method, final boolean usernameSignIn) {
    return new AccountAuthenticationPolicySnapshot(
        true, method, false, false, false, usernameSignIn, true, false, false);
  }

  @Test
  void theFormRendersWithTheAddressPrefilled() throws Exception {
    String body =
        mockMvc
            .perform(
                get("/o/{organizationId}/verify-email/resend", ORGANIZATION_ID)
                    .param("email", "lost@example.com"))
            .andExpect(status().isOk())
            .andExpect(view().name("identity/verify-email-resend"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains("lost@example.com").contains("type=\"email\"");
    verifyNoInteractions(useCase);
  }

  @Test
  void theFieldTakesAUsernameWhereUsernameSignInIsOn() throws Exception {
    when(policyProvider.policyFor(ORG)).thenReturn(policy(EmailVerificationMethod.LINK, true));

    String body =
        mockMvc
            .perform(get("/o/{organizationId}/verify-email/resend", ORGANIZATION_ID))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains("Username or email").contains("type=\"text\"");
  }

  // The answer is the same page whatever the address names; the use case decides afterwards.
  @Test
  void anAddressIsAlwaysAnsweredWithTheSameRedirectAndHandedToTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/verify-email/resend", ORGANIZATION_ID)
                .param("email", "anyone@example.com"))
        .andExpect(status().is3xxRedirection())
        .andExpect(
            redirectedUrl(
                "/o/" + ORGANIZATION_ID + "/verify-email/resend/sent?email=anyone%40example.com"));

    verify(useCase).handle(new ResendEmailVerificationCommand(ORG, "anyone@example.com"));
  }

  @Test
  void aUsernameGoesToTheUseCaseAsTyped() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/verify-email/resend", ORGANIZATION_ID)
                .param("email", "ana_perez"))
        .andExpect(status().is3xxRedirection());

    verify(useCase).handle(new ResendEmailVerificationCommand(ORG, "ana_perez"));
  }

  @Test
  void aBlankFieldRerendersTheFormWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(
            post("/o/{organizationId}/verify-email/resend", ORGANIZATION_ID).param("email", " "))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/verify-email-resend"))
        .andExpect(model().attributeHasFieldErrors("form", "email"));

    verifyNoInteractions(useCase);
  }

  @Test
  void theSentPageOffersTheCodeOnlyWhereTheOrganizationMailsOne() throws Exception {
    String link =
        mockMvc
            .perform(get("/o/{organizationId}/verify-email/resend/sent", ORGANIZATION_ID))
            .andExpect(status().isOk())
            .andExpect(view().name("identity/verify-email-resend-sent"))
            .andExpect(model().attribute("codeEntry", false))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(link).doesNotContain("Enter it here");

    when(policyProvider.policyFor(ORG)).thenReturn(policy(EmailVerificationMethod.BOTH, false));
    String code =
        mockMvc
            .perform(
                get("/o/{organizationId}/verify-email/resend/sent", ORGANIZATION_ID)
                    .param("email", "lost@example.com"))
            .andExpect(model().attribute("codeEntry", true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(code).contains("Enter it here").contains("../code?email=lost@example.com");
  }
}
