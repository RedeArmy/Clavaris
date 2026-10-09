package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.hamcrest.Matchers.hasProperty;
import static org.hamcrest.Matchers.is;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.model;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.getsessionpolicyfororganization.GetSessionPolicyForOrganizationUseCase;
import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.SetSessionPolicyForOrganizationCommand;
import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.SetSessionPolicyForOrganizationResult;
import com.clavaris.organization.application.usecase.setsessionpolicyfororganization.SetSessionPolicyForOrganizationUseCase;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.SessionPolicy;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.thymeleaf.spring6.SpringTemplateEngine;
import org.thymeleaf.spring6.templateresolver.SpringResourceTemplateResolver;
import org.thymeleaf.spring6.view.ThymeleafViewResolver;

/**
 * Same standalone MockMvc + real Thymeleaf setup as {@code PlatformRateLimitPolicyControllerTest}:
 * the page really renders, so a template that no longer parses fails here.
 */
class PlatformSessionPolicyControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();
  private static final String FORM = "sessionPolicyForm";
  private static final String VIEW = "organization/platform/organization-session-policy";

  private GetOrganizationForPlatformAccountUseCase getOrganization;
  private SetSessionPolicyForOrganizationUseCase setSessionPolicy;
  private GetSessionPolicyForOrganizationUseCase getSessionPolicy;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private Organization organization;

  @BeforeEach
  void setUp() {
    getOrganization = mock(GetOrganizationForPlatformAccountUseCase.class);
    setSessionPolicy = mock(SetSessionPolicyForOrganizationUseCase.class);
    getSessionPolicy = mock(GetSessionPolicyForOrganizationUseCase.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);

    organization = Organization.register("Acme Co", OWNER_ID);

    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(getOrganization.handle(any())).thenReturn(Optional.of(organization));
    when(getSessionPolicy.handle(any())).thenReturn(SessionPolicy.defaults(organization.id()));

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
                new PlatformSessionPolicyController(
                    getOrganization, setSessionPolicy, getSessionPolicy, currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private String path() {
    return "/platform/dashboard/organizations/" + organization.id() + "/session-policy";
  }

  // The form as the page posts it: an amount and a unit for each duration.
  private MockHttpServletRequestBuilder submit(
      final String lifetime,
      final String lifetimeUnit,
      final String inactivity,
      final String inactivityUnit,
      final String reverification) {
    return post(path())
        .param("maximumLifetimeValue", lifetime)
        .param("maximumLifetimeUnit", lifetimeUnit)
        .param("inactivityTimeoutValue", inactivity)
        .param("inactivityTimeoutUnit", inactivityUnit)
        .param("reverificationWindowMinutes", reverification)
        .param("multiSessionHandlingEnabled", "false");
  }

  private void savedAs(final int lifetime, final int inactivity, final int reverification) {
    SessionPolicy updated =
        SessionPolicy.define(organization.id(), lifetime, inactivity, reverification, false);
    when(setSessionPolicy.handle(any()))
        .thenReturn(new SetSessionPolicyForOrganizationResult(updated));
    when(getSessionPolicy.handle(any())).thenReturn(updated);
  }

  @Test
  void getShowsTheOrganizationsEffectiveSessionPolicy() throws Exception {
    SessionPolicy customized = SessionPolicy.define(organization.id(), 20_160, 1_440, 5, false);
    when(getSessionPolicy.handle(any())).thenReturn(customized);

    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(view().name(VIEW))
        .andExpect(model().attribute("sessionPolicy", customized))
        .andExpect(model().attribute("organizationName", "Acme Co"));
  }

  @Test
  void getShowsEachSavedDurationInTheLargestUnitThatStatesItExactly() throws Exception {
    // 20160 minutes is 2 weeks, 1440 is 1 day: never "20160" and "1440".
    when(getSessionPolicy.handle(any()))
        .thenReturn(SessionPolicy.define(organization.id(), 20_160, 1_440, 5, false));

    mockMvc
        .perform(get(path()))
        .andExpect(model().attribute(FORM, hasProperty("maximumLifetimeValue", is("2"))))
        .andExpect(model().attribute(FORM, hasProperty("maximumLifetimeUnit", is("WEEKS"))))
        .andExpect(model().attribute(FORM, hasProperty("inactivityTimeoutValue", is("1"))))
        .andExpect(model().attribute(FORM, hasProperty("inactivityTimeoutUnit", is("DAYS"))))
        .andExpect(model().attribute(FORM, hasProperty("reverificationWindowMinutes", is("5"))));
  }

  @Test
  void theSwitchIsCheckedByDefaultAndHasNoHiddenCompanionInput() throws Exception {
    // A hidden input after the checkbox would break the "checkbox + track" styling selector.
    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("checked=\"checked\"")))
        .andExpect(
            content()
                .string(
                    org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("_multiSessionHandlingEnabled"))));
  }

  @Test
  void theRenderedPageOffersEveryUnitAndASwitchRatherThanACheckbox() throws Exception {
    mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"HOURS\"")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("value=\"YEARS\"")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("clavaris-toggle__track")))
        .andExpect(content().string(org.hamcrest.Matchers.containsString("data-min-minutes=\"5\"")))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("data-max-minutes=\"5256000\"")));
  }

  @Test
  void getReturnsNotFoundWhenTheOrganizationIsNotOwnedByTheCurrentAccount() throws Exception {
    when(getOrganization.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(get(path())).andExpect(status().isNotFound());
  }

  @Test
  void plainPostRedirectsAfterUpdatingThePolicy() throws Exception {
    savedAs(20_160, 1_440, 5);

    mockMvc
        .perform(submit("2", "WEEKS", "1", "DAYS", "5"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(path()));

    verify(setSessionPolicy)
        .handle(
            new SetSessionPolicyForOrganizationCommand(
                organization.id(), 20_160, 1_440, 5, false, AuditActor.platformAccount(OWNER_ID)));
  }

  @Test
  void theUnitIsAppliedBeforeTheAmountIsSaved() throws Exception {
    savedAs(1_080, 1_080, 5);

    mockMvc
        .perform(submit("18", "HOURS", "1080", "MINUTES", "5"))
        .andExpect(status().is3xxRedirection());

    verify(setSessionPolicy)
        .handle(
            new SetSessionPolicyForOrganizationCommand(
                organization.id(), 1_080, 1_080, 5, false, AuditActor.platformAccount(OWNER_ID)));
  }

  @Test
  void htmxPostReturnsTheSessionPolicyFragmentShowingTheSavedValuesNormalised() throws Exception {
    savedAs(1_080, 1_080, 5);

    mockMvc
        .perform(submit("1080", "MINUTES", "1080", "MINUTES", "5").header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name(VIEW + " :: sessionPolicy"))
        // What was typed as 1080 minutes comes back as the 18 hours it is.
        .andExpect(model().attribute(FORM, hasProperty("maximumLifetimeValue", is("18"))))
        .andExpect(model().attribute(FORM, hasProperty("maximumLifetimeUnit", is("HOURS"))));
  }

  @Test
  void anOutOfRangeValueReRendersKeepingWhatWasTypedWithoutCallingTheUseCase() throws Exception {
    mockMvc
        .perform(submit("4", "MINUTES", "1", "DAYS", "5"))
        .andExpect(status().isOk())
        .andExpect(view().name(VIEW))
        .andExpect(model().attributeHasFieldErrors(FORM, "maximumLifetimeValue"))
        .andExpect(model().attribute(FORM, hasProperty("maximumLifetimeValue", is("4"))))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("Must be at least 5 minutes.")));

    verify(setSessionPolicy, never()).handle(any());
  }

  @Test
  void aValueThatFitsInOneUnitButNotAnotherIsRefusedInTheOneChosen() throws Exception {
    // 11 years is over the 10-year ceiling, though 11 on its own is fine as minutes.
    mockMvc
        .perform(submit("11", "YEARS", "1", "DAYS", "5"))
        .andExpect(model().attributeHasFieldErrors(FORM, "maximumLifetimeValue"))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("Must be at most 10 years.")));

    verify(setSessionPolicy, never()).handle(any());
  }

  @Test
  void anInactivityTimeoutLongerThanTheLifetimeIsRefused() throws Exception {
    mockMvc
        .perform(submit("1", "DAYS", "2", "DAYS", "5"))
        .andExpect(model().attributeHasFieldErrors(FORM, "inactivityTimeoutValue"));

    verify(setSessionPolicy, never()).handle(any());
  }

  @Test
  void aReverificationWindowOutsideOneToTenMinutesIsRefused() throws Exception {
    mockMvc
        .perform(submit("1", "WEEKS", "1", "DAYS", "11"))
        .andExpect(model().attributeHasFieldErrors(FORM, "reverificationWindowMinutes"))
        .andExpect(
            content().string(org.hamcrest.Matchers.containsString("Must be at most 10 minutes.")));

    verify(setSessionPolicy, never()).handle(any());
  }

  @Test
  void postReturnsNotFoundWhenTheOrganizationIsNotOwnedByTheCurrentAccount() throws Exception {
    when(getOrganization.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(submit("2", "WEEKS", "1", "DAYS", "5")).andExpect(status().isNotFound());

    verify(setSessionPolicy, never()).handle(any());
  }
}
