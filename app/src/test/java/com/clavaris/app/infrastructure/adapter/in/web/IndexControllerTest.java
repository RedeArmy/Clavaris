package com.clavaris.app.infrastructure.adapter.in.web;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.domain.model.PlatformAccountId;
import com.clavaris.identity.infrastructure.adapter.in.web.CurrentPlatformAccountResolver;
import java.util.Optional;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/**
 * Same standalone MockMvc + real Thymeleaf setup as {@code LoginControllerTest} (identity-module) —
 * a real render, not a mocked view layer, proves the {@code head} fragment reference in {@code
 * index.html} actually resolves rather than only compiling.
 *
 * <p>Live bug fix, 2026-09-26: {@code Sign in}/{@code Get started} used to render unconditionally,
 * even for an already-authenticated {@code PlatformAccount} — see {@link IndexController}'s own
 * Javadoc for the full fix (this page itself never redirects; only the buttons change).
 */
class IndexControllerTest {

  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    final GenericApplicationContext applicationContext = new GenericApplicationContext();
    applicationContext.refresh();

    final SpringResourceTemplateResolver templateResolver = new SpringResourceTemplateResolver();
    templateResolver.setApplicationContext(applicationContext);
    templateResolver.setPrefix("classpath:/templates/");
    templateResolver.setSuffix(".html");

    final SpringTemplateEngine templateEngine = new SpringTemplateEngine();
    templateEngine.setTemplateResolver(templateResolver);

    final ThymeleafViewResolver viewResolver = new ThymeleafViewResolver();
    viewResolver.setTemplateEngine(templateEngine);

    mockMvc =
        MockMvcBuilders.standaloneSetup(new IndexController(currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void rootPathRendersTheRealIndexTemplate() throws Exception {
    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.empty());

    mockMvc.perform(get("/")).andExpect(status().isOk()).andExpect(view().name("index"));
  }

  @Test
  void anUnauthenticatedVisitorSeesSignInAndGetStarted() throws Exception {
    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Sign in")))
        .andExpect(content().string(containsString("Get started")))
        .andExpect(content().string(Matchers.not(containsString("Go to dashboard"))));
  }

  // Live bug fix, 2026-09-26: an already-signed-in visitor used to see "Sign in"/"Get started"
  // here too, both of which (before this fix) walked them straight back into logging in again or
  // creating a second, unrelated account — see PlatformLoginController/
  // RegisterPlatformAccountController's own identical fix.
  @Test
  void anAuthenticatedVisitorSeesGoToDashboardInstead() throws Exception {
    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(PlatformAccountId.newId()));

    mockMvc
        .perform(get("/"))
        .andExpect(status().isOk())
        .andExpect(content().string(containsString("Go to dashboard")))
        .andExpect(content().string(containsString("/platform/dashboard")))
        .andExpect(content().string(Matchers.not(containsString("Sign in"))))
        .andExpect(content().string(Matchers.not(containsString("Get started"))));
  }
}
