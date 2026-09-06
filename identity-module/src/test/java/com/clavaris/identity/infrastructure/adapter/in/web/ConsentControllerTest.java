package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.application.usecase.resolveclientbranding.ClientBrandingProvider;
import com.clavaris.identity.application.usecase.resolveclientbranding.ClientBrandingSnapshot;
import com.clavaris.identity.application.usecase.resolveorganizationforclient.OrganizationForClientResolver;
import com.clavaris.identity.domain.model.OrganizationId;
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
 * Same standalone MockMvc + real Thymeleaf setup as {@link LoginControllerTest} — see its Javadoc.
 */
class ConsentControllerTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();
  private static final String CLIENT_ID = "jobseeker-web";

  private OrganizationForClientResolver organizationForClient;
  private ClientBrandingProvider clientBrandingProvider;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    organizationForClient = mock(OrganizationForClientResolver.class);
    clientBrandingProvider = mock(ClientBrandingProvider.class);
    when(clientBrandingProvider.brandingFor(any(), any()))
        .thenReturn(ClientBrandingSnapshot.unconfigured());

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
                new ConsentController(organizationForClient, clientBrandingProvider))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void resolvesTheOrganizationFromClientIdAndRendersTheAuthorizeUriAndRequestedScopes()
      throws Exception {
    when(organizationForClient.resolve(CLIENT_ID))
        .thenReturn(Optional.of(new OrganizationId(ORGANIZATION_ID)));

    mockMvc
        .perform(
            get("/oauth2/consent")
                .param("client_id", CLIENT_ID)
                .param("state", "opaque-state")
                .param("scope", "openid profile email"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/consent"))
        .andExpect(model().attribute("clientId", CLIENT_ID))
        .andExpect(model().attribute("state", "opaque-state"))
        .andExpect(model().attribute("authorizeUri", "/o/" + ORGANIZATION_ID + "/oauth2/authorize"))
        // TD-SEC-011: openid is never shown as a scope to consent to — see this class's own
        // Javadoc, matching SAS's own former DefaultConsentPage.
        .andExpect(model().attribute("scopes", List.of("profile", "email")));
  }

  @Test
  void aMissingScopeParamRendersAnEmptyScopeList() throws Exception {
    when(organizationForClient.resolve(CLIENT_ID))
        .thenReturn(Optional.of(new OrganizationId(ORGANIZATION_ID)));

    mockMvc
        .perform(get("/oauth2/consent").param("client_id", CLIENT_ID).param("state", "s"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("scopes", List.of()));
  }

  // A directly-crafted request with an unresolvable client_id — SAS itself never reaches this
  // page without already validating client_id against a real RegisteredClient first.
  @Test
  void anUnknownClientIdRendersAGenericErrorWithoutLeakingWhichCheckFailed() throws Exception {
    when(organizationForClient.resolve("bogus-client")).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/oauth2/consent").param("client_id", "bogus-client").param("state", "s"))
        .andExpect(status().isBadRequest())
        .andExpect(view().name("identity/consent-error"));
  }
}
