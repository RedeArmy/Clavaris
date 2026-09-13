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

import com.clavaris.organization.application.usecase.createorganization.SigningKeyProvisioner;
import com.clavaris.organization.application.usecase.createproductionenvironment.CreateProductionEnvironmentResult;
import com.clavaris.organization.application.usecase.createproductionenvironment.CreateProductionEnvironmentUseCase;
import com.clavaris.organization.application.usecase.createproductionenvironment.OrganizationAlreadyHasLinkedEnvironmentException;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.domain.model.Organization;
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
 * PlatformOrganizationDetailControllerTest}.
 */
class PlatformCreateProductionEnvironmentControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();

  private GetOrganizationForPlatformAccountUseCase getOrganization;
  private CreateProductionEnvironmentUseCase createProductionEnvironment;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private Organization organization;

  @BeforeEach
  void setUp() {
    getOrganization = mock(GetOrganizationForPlatformAccountUseCase.class);
    createProductionEnvironment = mock(CreateProductionEnvironmentUseCase.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organization = Organization.register("Acme (dev)", OWNER_ID);

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
                new PlatformCreateProductionEnvironmentController(
                    getOrganization, createProductionEnvironment, currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private String basePath() {
    return "/platform/dashboard/organizations/" + organization.id() + "/promote-to-production";
  }

  @Test
  void showsTheForm() throws Exception {
    mockMvc
        .perform(get(basePath()))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/promote-to-production"))
        .andExpect(model().attribute("organization", organization));
  }

  @Test
  void returnsNotFoundWhenTheOrganizationIsNotOwnedByTheCurrentAccount() throws Exception {
    when(getOrganization.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(get(basePath())).andExpect(status().isNotFound());
  }

  @Test
  void plainCreatePostRedirectsToTheNewProductionOrganization() throws Exception {
    Organization production =
        Organization.registerProductionEnvironment("Acme", OWNER_ID, organization.id());
    SigningKeyProvisioner.ProvisionedSigningKey signingKey =
        new SigningKeyProvisioner.ProvisionedSigningKey(UUID.randomUUID(), "kid-1", "RS256");
    when(createProductionEnvironment.handle(any()))
        .thenReturn(new CreateProductionEnvironmentResult(production, signingKey));

    mockMvc
        .perform(post(basePath()).param("name", "Acme"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl("/platform/dashboard/organizations/" + production.id()));

    verify(createProductionEnvironment).handle(any());
  }

  @Test
  void createWithNoNameRendersAnErrorWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(post(basePath()).param("name", ""))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/promote-to-production"));

    verify(createProductionEnvironment, never()).handle(any());
  }

  @Test
  void createWhenNotEligibleRendersAnInlineErrorWithoutFailing() throws Exception {
    when(createProductionEnvironment.handle(any()))
        .thenThrow(new OrganizationAlreadyHasLinkedEnvironmentException(organization.id()));

    mockMvc
        .perform(post(basePath()).param("name", "Acme"))
        .andExpect(status().isOk())
        .andExpect(view().name("organization/platform/promote-to-production"))
        .andExpect(model().attribute("notEligibleError", true));
  }
}
