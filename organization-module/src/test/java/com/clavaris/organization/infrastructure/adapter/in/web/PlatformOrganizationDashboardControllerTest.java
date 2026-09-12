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

import com.clavaris.organization.application.usecase.createorganization.CreateOrganizationResult;
import com.clavaris.organization.application.usecase.createorganization.CreateOrganizationUseCase;
import com.clavaris.organization.application.usecase.listorganizationsforplatformaccount.ListOrganizationsForPlatformAccountUseCase;
import com.clavaris.organization.domain.model.Organization;
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
 * Same standalone MockMvc + real Thymeleaf setup as identity-module's own {@code
 * LoginControllerTest}/{@code ConsentControllerTest} — real template rendering, not a mocked view
 * resolver, so a broken {@code th:*} expression or fragment reference fails this test, not just a
 * live server.
 */
class PlatformOrganizationDashboardControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();

  private CreateOrganizationUseCase createOrganization;
  private ListOrganizationsForPlatformAccountUseCase listOrganizations;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    createOrganization = mock(CreateOrganizationUseCase.class);
    listOrganizations = mock(ListOrganizationsForPlatformAccountUseCase.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);
    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(listOrganizations.handle(any())).thenReturn(List.of());

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
                new PlatformOrganizationDashboardController(
                    createOrganization, listOrganizations, currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void getRendersTheEmptyStateWhenTheAccountOwnsNoOrganizations() throws Exception {
    mockMvc
        .perform(get("/platform/dashboard"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/dashboard"))
        .andExpect(model().attribute("organizations", List.of()));
  }

  @Test
  void getListsEveryOrganizationTheAccountOwns() throws Exception {
    Organization organization = Organization.register("Acme Co", OWNER_ID);
    when(listOrganizations.handle(any())).thenReturn(List.of(organization));

    mockMvc
        .perform(get("/platform/dashboard"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("organizations", List.of(organization)));
  }

  @Test
  void plainFormPostRedirectsAfterCreatingAnOrganization() throws Exception {
    CreateOrganizationResult result = mock(CreateOrganizationResult.class);
    when(createOrganization.handle(any())).thenReturn(result);

    mockMvc
        .perform(post("/platform/dashboard").param("name", "New Co"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/platform/dashboard"));

    verify(createOrganization).handle(any());
  }

  @Test
  void plainFormPostWithNoNameReRendersTheFullPageWithoutCreatingAnything() throws Exception {
    mockMvc
        .perform(post("/platform/dashboard").param("name", ""))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/dashboard"));

    verify(createOrganization, never()).handle(any());
  }

  // ADR-0025: an HTMX-originated POST (HX-Request: true) must get back just the content fragment,
  // not a redirect — the whole point of hx-target/hx-swap on the page's own form.
  @Test
  void htmxPostReturnsTheContentFragmentInsteadOfARedirect() throws Exception {
    CreateOrganizationResult result = mock(CreateOrganizationResult.class);
    when(createOrganization.handle(any())).thenReturn(result);

    mockMvc
        .perform(post("/platform/dashboard").param("name", "New Co").header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/dashboard :: content"));

    verify(createOrganization).handle(any());
  }

  @Test
  void htmxPostWithNoNameReturnsTheContentFragmentNotAFullPage() throws Exception {
    mockMvc
        .perform(post("/platform/dashboard").param("name", "").header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/dashboard :: content"));

    verify(createOrganization, never()).handle(any());
  }
}
