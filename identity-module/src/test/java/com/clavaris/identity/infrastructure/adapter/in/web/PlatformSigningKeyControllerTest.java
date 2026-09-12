package com.clavaris.identity.infrastructure.adapter.in.web;

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

import com.clavaris.identity.application.usecase.listsigningkeysfororganization.ListSigningKeysForOrganizationUseCase;
import com.clavaris.identity.application.usecase.rotatesigningkeyfororganization.NoActiveSigningKeyException;
import com.clavaris.identity.application.usecase.rotatesigningkeyfororganization.RotateSigningKeyForOrganizationResult;
import com.clavaris.identity.application.usecase.rotatesigningkeyfororganization.RotateSigningKeyForOrganizationUseCase;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.domain.model.SigningKey;
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
 * Same standalone MockMvc + real Thymeleaf setup as {@code PlatformAccountSessionsControllerTest}.
 * No purge tests here — see {@code PlatformSigningKeyController}'s own Javadoc for why that action
 * has no button/endpoint on this page at all.
 */
class PlatformSigningKeyControllerTest {

  private static final PlatformAccountId OWNER_ID = PlatformAccountId.newId();

  private ListSigningKeysForOrganizationUseCase listKeys;
  private RotateSigningKeyForOrganizationUseCase rotateKey;
  private OrganizationForPlatformAccountResolver organizationResolver;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private UUID organizationId;

  @BeforeEach
  void setUp() {
    listKeys = mock(ListSigningKeysForOrganizationUseCase.class);
    rotateKey = mock(RotateSigningKeyForOrganizationUseCase.class);
    organizationResolver = mock(OrganizationForPlatformAccountResolver.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organizationId = UUID.randomUUID();

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.of("Acme Co"));
    when(listKeys.handle(any())).thenReturn(List.of());

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
                new PlatformSigningKeyController(
                    listKeys, rotateKey, organizationResolver, currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private String basePath() {
    return "/platform/dashboard/organizations/" + organizationId + "/signing-keys";
  }

  private SigningKey sampleActiveKey() {
    return SigningKey.activate(new OrganizationId(organizationId), "a-kid", "RS256");
  }

  @Test
  void showsTheOrganizationsSigningKeys() throws Exception {
    when(listKeys.handle(any())).thenReturn(List.of(sampleActiveKey()));

    mockMvc
        .perform(get(basePath()))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/organization-signing-keys"))
        .andExpect(model().attribute("organizationName", "Acme Co"));
  }

  @Test
  void returnsNotFoundWhenTheOrganizationIsNotOwnedByTheCurrentAccount() throws Exception {
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.empty());

    mockMvc.perform(get(basePath())).andExpect(status().isNotFound());
  }

  @Test
  void plainRotatePostRedirectsOnSuccess() throws Exception {
    SigningKey rotated = sampleActiveKey();
    when(rotateKey.handle(any()))
        .thenReturn(new RotateSigningKeyForOrganizationResult(rotated, "previous-kid"));

    mockMvc
        .perform(post(basePath() + "/rotate"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(basePath()));

    verify(rotateKey).handle(any());
  }

  @Test
  void htmxRotatePostReturnsTheKeysFragment() throws Exception {
    SigningKey rotated = sampleActiveKey();
    when(rotateKey.handle(any()))
        .thenReturn(new RotateSigningKeyForOrganizationResult(rotated, "previous-kid"));

    mockMvc
        .perform(post(basePath() + "/rotate").header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/platform/organization-signing-keys :: keys"));
  }

  @Test
  void rotateReturnsNotFoundWhenTheOrganizationHasNoActiveKey() throws Exception {
    when(rotateKey.handle(any()))
        .thenThrow(new NoActiveSigningKeyException(new OrganizationId(organizationId)));

    mockMvc.perform(post(basePath() + "/rotate")).andExpect(status().isNotFound());
  }

  @Test
  void rotateReturnsNotFoundWhenTheOrganizationIsNotOwnedByTheCurrentAccount() throws Exception {
    when(organizationResolver.resolveName(any(), any())).thenReturn(Optional.empty());

    mockMvc.perform(post(basePath() + "/rotate")).andExpect(status().isNotFound());

    verify(rotateKey, never()).handle(any());
  }
}
