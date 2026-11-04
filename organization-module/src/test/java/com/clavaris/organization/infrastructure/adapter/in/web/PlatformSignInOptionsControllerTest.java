package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
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

import com.clavaris.organization.application.usecase.getaccountauthenticationpolicyfororganization.GetAccountAuthenticationPolicyForOrganizationUseCase;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.PasswordOptionalRequiresPasswordlessSignInException;
import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.SetAccountAuthenticationPolicyForOrganizationCommand;
import com.clavaris.organization.application.usecase.setaccountauthenticationpolicyfororganization.SetAccountAuthenticationPolicyForOrganizationUseCase;
import com.clavaris.organization.domain.model.AccountAuthenticationPolicy;
import com.clavaris.organization.domain.model.AccountAuthenticationSettings;
import com.clavaris.organization.domain.model.EmailVerificationMethod;
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
 * Same standalone MockMvc + real Thymeleaf setup as {@code PlatformSessionPolicyControllerTest}:
 * the page really renders, so a template that no longer parses fails here.
 */
class PlatformSignInOptionsControllerTest {

  private static final UUID OWNER_ID = UUID.randomUUID();
  private static final String VIEW = "organization/platform/organization-sign-in-options";

  private GetOrganizationForPlatformAccountUseCase getOrganization;
  private GetAccountAuthenticationPolicyForOrganizationUseCase getPolicy;
  private SetAccountAuthenticationPolicyForOrganizationUseCase setPolicy;
  private CurrentPlatformAccountResolver currentPlatformAccount;
  private MockMvc mockMvc;
  private Organization organization;

  @BeforeEach
  void setUp() {
    getOrganization = mock(GetOrganizationForPlatformAccountUseCase.class);
    getPolicy = mock(GetAccountAuthenticationPolicyForOrganizationUseCase.class);
    setPolicy = mock(SetAccountAuthenticationPolicyForOrganizationUseCase.class);
    currentPlatformAccount = mock(CurrentPlatformAccountResolver.class);
    organization = Organization.register("Acme Co", OWNER_ID);
    when(currentPlatformAccount.resolve(any())).thenReturn(Optional.of(OWNER_ID));
    when(getOrganization.handle(any())).thenReturn(Optional.of(organization));
    when(getPolicy.handle(any()))
        .thenReturn(AccountAuthenticationPolicy.defaults(organization.id()));

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
                new PlatformSignInOptionsController(
                    getOrganization, getPolicy, setPolicy, currentPlatformAccount))
            .setViewResolvers(viewResolver)
            .build();
  }

  private String path() {
    return "/platform/dashboard/organizations/" + organization.id() + "/sign-in-options";
  }

  @Test
  void theSwitchIsShownOffForAnOrganizationThatNeverTurnedItOn() throws Exception {
    String body =
        mockMvc
            .perform(get(path()))
            .andExpect(status().isOk())
            .andExpect(view().name(VIEW))
            .andExpect(model().attribute("accessCodeEnabled", false))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains("Sign in with a one-time access code");
    assertThat(accessCodeInput(body)).doesNotContain("checked");
  }

  @Test
  void theSwitchIsShownOnWhereTheCodeIsOn() throws Exception {
    when(getPolicy.handle(any()))
        .thenReturn(
            AccountAuthenticationPolicy.define(
                organization.id(),
                new AccountAuthenticationSettings(
                    false,
                    EmailVerificationMethod.LINK,
                    true,
                    false,
                    false,
                    true,
                    true,
                    false,
                    false)));

    String body =
        mockMvc
            .perform(get(path()))
            .andExpect(model().attribute("accessCodeEnabled", true))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(accessCodeInput(body)).contains("checked");
  }

  // The use case replaces the whole policy, so the command is the saved one with only this switch
  // changed: what an operator set through the API is left alone.
  @Test
  void turningItOnChangesOnlyTheCodeSwitchAndKeepsTheRestOfThePolicy() throws Exception {
    when(getPolicy.handle(any()))
        .thenReturn(
            AccountAuthenticationPolicy.define(
                    organization.id(),
                    new AccountAuthenticationSettings(
                        true,
                        EmailVerificationMethod.CODE,
                        false,
                        true,
                        true,
                        true,
                        false,
                        true,
                        true))
                .withSelfServiceEmailChange(false)
                .withCodeAfterPasswordRequired(true));

    mockMvc
        .perform(
            post(path()).param("accessCodeEnabled", "true").param("usernameSignInEnabled", "true"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(path()));

    verify(setPolicy)
        .handle(
            argThat(
                (SetAccountAuthenticationPolicyForOrganizationCommand command) ->
                    command.emailCodeSignInEnabled()
                        && command.emailVerificationRequiredAtSignIn()
                        && command.emailVerificationMethod() == EmailVerificationMethod.CODE
                        && command.emailLinkSignInEnabled()
                        && command.usernameRequired()
                        && command.usernameSignInEnabled()
                        && !command.passwordAtSignUpEnabled()
                        && command.deviceTrustEnabled()
                        && command.selfRegistrationRequiresApproval()
                        && !command.selfServiceEmailChangeEnabled()
                        && command.codeAfterPasswordRequired()));
  }

  @Test
  void theAuthenticatorSwitchIsShownAndSavedIndependentlyOfTheCodeSwitch() throws Exception {
    when(getPolicy.handle(any()))
        .thenReturn(AccountAuthenticationPolicy.defaults(organization.id()).withTotpEnabled(true));

    String body =
        mockMvc
            .perform(get(path()))
            .andExpect(model().attribute("totpEnabled", true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).contains("Let Accounts use an authenticator app");

    mockMvc
        .perform(post(path()).param("totpEnabled", "true"))
        .andExpect(status().is3xxRedirection());
    verify(setPolicy)
        .handle(
            argThat(
                (SetAccountAuthenticationPolicyForOrganizationCommand command) ->
                    command.totpEnabled() && !command.emailCodeSignInEnabled()));
  }

  @Test
  void thePasskeySwitchesAreShownOnByDefaultAndSavedIndependentlyOfTheOthers() throws Exception {
    when(getPolicy.handle(any()))
        .thenReturn(AccountAuthenticationPolicy.defaults(organization.id()));

    String body =
        mockMvc
            .perform(get(path()))
            .andExpect(model().attribute("passkeysEnabled", true))
            .andExpect(model().attribute("passkeyOfferEnabled", true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body)
        .contains("Let Accounts use passkeys")
        .contains("Offer a passkey after a sign-in by email code or link");

    mockMvc
        .perform(post(path()).param("passkeysEnabled", "true"))
        .andExpect(status().is3xxRedirection());
    verify(setPolicy)
        .handle(
            argThat(
                (SetAccountAuthenticationPolicyForOrganizationCommand command) ->
                    command.passkeysEnabled() && !command.passkeyOfferEnabled()));
  }

  // ADR-0032 built the switch; this is the page that lets an operator set it.
  @Test
  void theUsernameSwitchIsShownAndSavedIndependentlyOfTheOthers() throws Exception {
    when(getPolicy.handle(any()))
        .thenReturn(
            AccountAuthenticationPolicy.define(
                organization.id(), AccountAuthenticationSettings.forNewOrganization()));

    String body =
        mockMvc
            .perform(get(path()))
            .andExpect(model().attribute("usernameSignInEnabled", true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(body).contains("Sign in with a username");
    assertThat(inputOf(body, "sign-in-options-username")).contains("checked");

    mockMvc
        .perform(post(path()).param("usernameSignInEnabled", "true"))
        .andExpect(status().is3xxRedirection());
    verify(setPolicy)
        .handle(
            argThat(
                (SetAccountAuthenticationPolicyForOrganizationCommand command) ->
                    command.usernameSignInEnabled() && !command.emailCodeSignInEnabled()));
  }

  @Test
  void anUncheckedUsernameBoxTurnsUsernameSignInOffAndLeavesTheRestOfTheNamePolicyAlone()
      throws Exception {
    when(getPolicy.handle(any()))
        .thenReturn(
            AccountAuthenticationPolicy.define(
                organization.id(),
                new AccountAuthenticationSettings(
                    false,
                    EmailVerificationMethod.LINK,
                    false,
                    false,
                    true,
                    true,
                    true,
                    false,
                    false)));

    mockMvc.perform(post(path())).andExpect(status().is3xxRedirection());

    verify(setPolicy)
        .handle(
            argThat(
                (SetAccountAuthenticationPolicyForOrganizationCommand command) ->
                    !command.usernameSignInEnabled() && command.usernameRequired()));
  }

  @Test
  void anUncheckedAuthenticatorBoxTurnsItOff() throws Exception {
    when(getPolicy.handle(any()))
        .thenReturn(AccountAuthenticationPolicy.defaults(organization.id()).withTotpEnabled(true));

    mockMvc
        .perform(post(path()).param("accessCodeEnabled", "true"))
        .andExpect(status().is3xxRedirection());

    verify(setPolicy)
        .handle(
            argThat(
                (SetAccountAuthenticationPolicyForOrganizationCommand command) ->
                    !command.totpEnabled() && command.emailCodeSignInEnabled()));
  }

  @Test
  void anUncheckedBoxTurnsItOff() throws Exception {
    mockMvc.perform(post(path())).andExpect(status().is3xxRedirection());

    verify(setPolicy)
        .handle(
            argThat(
                (SetAccountAuthenticationPolicyForOrganizationCommand command) ->
                    !command.emailCodeSignInEnabled()));
  }

  @Test
  void anHtmxRequestGetsTheFragmentBack() throws Exception {
    mockMvc
        .perform(post(path()).param("accessCodeEnabled", "true").header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name(VIEW + " :: signInOptions"));
  }

  // Turning it off would leave an Organization whose sign-up has no password with no way in.
  @Test
  void theRuleThatASignUpWithoutAPasswordKeepsAPasswordlessMethodIsShownNotThrown()
      throws Exception {
    doThrow(new PasswordOptionalRequiresPasswordlessSignInException())
        .when(setPolicy)
        .handle(any());

    String body =
        mockMvc
            .perform(post(path()))
            .andExpect(status().isOk())
            .andExpect(view().name(VIEW))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains("needs the one-time access code");
  }

  @Test
  void anOrganizationThatIsNotTheCallersIsNotFoundAndNothingIsChanged() throws Exception {
    when(getOrganization.handle(any())).thenReturn(Optional.empty());

    mockMvc.perform(get(path())).andExpect(status().isNotFound());
    mockMvc
        .perform(post(path()).param("accessCodeEnabled", "true"))
        .andExpect(status().isNotFound());

    verify(setPolicy, never()).handle(any());
  }

  // The page now carries other switches that are checked by default, so look at one input.
  private static String accessCodeInput(final String body) {
    return inputOf(body, "sign-in-options-access-code");
  }

  private static String inputOf(final String body, final String id) {
    final int start = body.indexOf("id=\"" + id + "\"");
    return body.substring(start, body.indexOf("/>", start));
  }
}
