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

import com.clavaris.organization.application.usecase.deleteorganization.DeleteOrganizationUseCase;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.domain.model.Organization;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/**
 * Same standalone MockMvc + real Thymeleaf setup as {@link
 * PlatformOrganizationDetailControllerTest}. A real {@link jakarta.servlet.http.HttpSession}
 * (MockMvc's own default, backed by a real {@code MockHttpSession}) is what makes {@link
 * DeleteOrganizationConfirmationTokens} actually exercisable here — session state carries across
 * the GET (issues a token) and the following POST (consumes it) within one {@code
 * .session(...)}-shared session, same as a real browser round trip.
 */
class PlatformDeleteOrganizationControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();

  private GetOrganizationForPlatformAccountUseCase getOrganization;
  private DeleteOrganizationUseCase deleteOrganization;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private Organization organization;

  @BeforeEach
  void setUp() {
    getOrganization = mock(GetOrganizationForPlatformAccountUseCase.class);
    deleteOrganization = mock(DeleteOrganizationUseCase.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organization = Organization.register("Acme Co", OWNER_ID);

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(getOrganization.handle(any())).thenReturn(Optional.of(organization));

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
                new PlatformDeleteOrganizationController(
                    getOrganization, deleteOrganization, currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private String basePath() {
    return "/platform/dashboard/organizations/" + organization.id() + "/delete";
  }

  @Test
  void showsTheConfirmationPageWithAFreshToken() throws Exception {
    mockMvc
        .perform(get(basePath()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/delete-organization-confirm"))
        .andExpect(model().attribute("organization", organization))
        .andExpect(model().attributeExists("confirmationToken"));
  }

  @Test
  void returnsNotFoundWhenTheOrganizationIsNotOwnedByTheCurrentAccount() throws Exception {
    when(getOrganization.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(get(basePath())).andExpect(status().isNotFound());
  }

  @Test
  void aValidTokenAndMatchingNameDeletesAndRedirectsToTheList() throws Exception {
    MvcResult confirmPage = mockMvc.perform(get(basePath())).andReturn();
    String token = (String) confirmPage.getModelAndView().getModel().get("confirmationToken");

    mockMvc
        .perform(
            post(basePath())
                .session(
                    (org.springframework.mock.web.MockHttpSession)
                        confirmPage.getRequest().getSession())
                .param("confirmationToken", token)
                .param("confirmedName", organization.name()))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/platform/dashboard"));

    verify(deleteOrganization).handle(any());
  }

  @Test
  void aTokenCannotBeReusedForASecondDeleteAttempt() throws Exception {
    MvcResult confirmPage = mockMvc.perform(get(basePath())).andReturn();
    String token = (String) confirmPage.getModelAndView().getModel().get("confirmationToken");
    org.springframework.mock.web.MockHttpSession session =
        (org.springframework.mock.web.MockHttpSession) confirmPage.getRequest().getSession();

    mockMvc.perform(
        post(basePath())
            .session(session)
            .param("confirmationToken", token)
            .param("confirmedName", organization.name()));

    mockMvc
        .perform(
            post(basePath())
                .session(session)
                .param("confirmationToken", token)
                .param("confirmedName", organization.name()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/delete-organization-confirm"))
        .andExpect(model().attribute("confirmationExpiredError", true));

    verify(deleteOrganization).handle(any());
  }

  @Test
  void aWrongTokenRendersAnInlineErrorWithoutDeletingAnything() throws Exception {
    MvcResult confirmPage = mockMvc.perform(get(basePath())).andReturn();
    org.springframework.mock.web.MockHttpSession session =
        (org.springframework.mock.web.MockHttpSession) confirmPage.getRequest().getSession();

    mockMvc
        .perform(
            post(basePath())
                .session(session)
                .param("confirmationToken", "not-the-real-token")
                .param("confirmedName", organization.name()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/delete-organization-confirm"))
        .andExpect(model().attribute("confirmationExpiredError", true));

    verify(deleteOrganization, never()).handle(any());
  }

  @Test
  void aMismatchedNameRendersAnInlineErrorWithoutDeletingAnything() throws Exception {
    MvcResult confirmPage = mockMvc.perform(get(basePath())).andReturn();
    String token = (String) confirmPage.getModelAndView().getModel().get("confirmationToken");
    org.springframework.mock.web.MockHttpSession session =
        (org.springframework.mock.web.MockHttpSession) confirmPage.getRequest().getSession();

    mockMvc
        .perform(
            post(basePath())
                .session(session)
                .param("confirmationToken", token)
                .param("confirmedName", "Not The Right Name"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/delete-organization-confirm"))
        .andExpect(model().attribute("nameMismatchError", true));

    verify(deleteOrganization, never()).handle(any());
  }
}
