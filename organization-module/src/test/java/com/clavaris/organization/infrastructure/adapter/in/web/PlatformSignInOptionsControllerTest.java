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

  // Every switch the page carries, by the id of its input, so a template that loses one fails here.
  private static final String[] SWITCH_IDS = {
    "sign-in-options-access-code",
    "sign-in-options-username",
    "sign-in-options-passkeys",
    "sign-in-options-passkey-offer",
    "sign-in-options-totp",
    "sign-in-options-verification-required",
    "sign-in-options-email-link",
    "sign-in-options-device-trust",
    "sign-in-options-code-after-password",
    "sign-in-options-password-sign-up",
    "sign-in-options-username-required",
    "sign-in-options-approval",
    "sign-in-options-email-change"
  };

  private String page() throws Exception {
    return mockMvc
        .perform(get(path()))
        .andExpect(status().isOk())
        .andExpect(view().name(VIEW))
        .andReturn()
        .getResponse()
        .getContentAsString();
  }

  private static AccountAuthenticationPolicy everythingStored(final UUID organizationId) {
    return AccountAuthenticationPolicy.define(
            organizationId,
            new AccountAuthenticationSettings(
                true, EmailVerificationMethod.BOTH, true, true, true, true, false, true, true))
        .withSelfServiceEmailChange(false)
        .withCodeAfterPasswordRequired(true)
        .withTotpEnabled(true)
        .withPasskeysEnabled(false)
        .withPasskeyOfferEnabled(false);
  }

  @Test
  void thePageShowsEverySwitchUnderItsGroupedAccountAccessCard() throws Exception {
    final String body = page();

    assertThat(body)
        .contains("Account access")
        .contains("Email verification")
        .contains("Passwordless sign-in")
        .contains("Extra checks at sign-in")
        .contains("Changes by the Account");
    for (final String id : SWITCH_IDS) {
      assertThat(body).as("the switch " + id).contains("id=\"" + id + "\"");
    }
  }

  @Test
  void aNewPolicyIsShownAsStoredWithEachSwitchOnlyWhereItIsOn() throws Exception {
    final String body = page();

    assertThat(inputOf(body, "sign-in-options-access-code")).doesNotContain("checked");
    assertThat(inputOf(body, "sign-in-options-verification-required")).doesNotContain("checked");
    assertThat(inputOf(body, "sign-in-options-device-trust")).doesNotContain("checked");
    assertThat(inputOf(body, "sign-in-options-approval")).doesNotContain("checked");
    assertThat(inputOf(body, "sign-in-options-passkeys")).contains("checked");
    assertThat(inputOf(body, "sign-in-options-email-change")).contains("checked");
  }

  @Test
  void aStoredPolicyFillsInEverySwitchAndTheMethod() throws Exception {
    when(getPolicy.handle(any())).thenReturn(everythingStored(organization.id()));

    final String body = page();

    for (final String id :
        new String[] {
          "sign-in-options-access-code",
          "sign-in-options-username",
          "sign-in-options-totp",
          "sign-in-options-verification-required",
          "sign-in-options-email-link",
          "sign-in-options-device-trust",
          "sign-in-options-code-after-password",
          "sign-in-options-username-required",
          "sign-in-options-approval"
        }) {
      assertThat(inputOf(body, id)).as(id).contains("checked");
    }
    for (final String id :
        new String[] {
          "sign-in-options-passkeys",
          "sign-in-options-passkey-offer",
          "sign-in-options-password-sign-up",
          "sign-in-options-email-change"
        }) {
      assertThat(inputOf(body, id)).as(id).doesNotContain("checked");
    }
    assertThat(body).containsPattern("<option value=\"BOTH\"\\s+selected=\"selected\"");
  }

  @Test
  void savingSendsEverySwitchAsSubmittedAndNothingFromTheStoredPolicy() throws Exception {
    mockMvc
        .perform(
            post(path())
                .param("emailVerificationRequiredAtSignIn", "true")
                .param("emailVerificationMethod", "CODE")
                .param("accessCodeEnabled", "true")
                .param("emailLinkSignInEnabled", "true")
                .param("usernameRequired", "true")
                .param("usernameSignInEnabled", "true")
                .param("passwordAtSignUpEnabled", "true")
                .param("deviceTrustEnabled", "true")
                .param("selfRegistrationRequiresApproval", "true")
                .param("selfServiceEmailChangeEnabled", "true")
                .param("codeAfterPasswordRequired", "true")
                .param("totpEnabled", "true")
                .param("passkeysEnabled", "true")
                .param("passkeyOfferEnabled", "true"))
        .andExpect(status().is3xxRedirection())
        .andExpect(redirectedUrl(path()));

    verify(setPolicy)
        .handle(
            argThat(
                (SetAccountAuthenticationPolicyForOrganizationCommand command) ->
                    command.organizationId().equals(organization.id())
                        && command.emailVerificationRequiredAtSignIn()
                        && command.emailVerificationMethod() == EmailVerificationMethod.CODE
                        && command.emailCodeSignInEnabled()
                        && command.emailLinkSignInEnabled()
                        && command.usernameRequired()
                        && command.usernameSignInEnabled()
                        && command.passwordAtSignUpEnabled()
                        && command.deviceTrustEnabled()
                        && command.selfRegistrationRequiresApproval()
                        && command.selfServiceEmailChangeEnabled()
                        && command.codeAfterPasswordRequired()
                        && command.totpEnabled()
                        && command.passkeysEnabled()
                        && command.passkeyOfferEnabled()));
  }

  // An unchecked box is simply absent from a post, so the whole page is what is saved.
  @Test
  void everythingUncheckedTurnsEverythingOffAndTheMethodFallsBackToTheLink() throws Exception {
    when(getPolicy.handle(any())).thenReturn(everythingStored(organization.id()));

    mockMvc.perform(post(path())).andExpect(status().is3xxRedirection());

    verify(setPolicy)
        .handle(
            argThat(
                (SetAccountAuthenticationPolicyForOrganizationCommand command) ->
                    !command.emailVerificationRequiredAtSignIn()
                        && command.emailVerificationMethod() == EmailVerificationMethod.LINK
                        && !command.emailCodeSignInEnabled()
                        && !command.emailLinkSignInEnabled()
                        && !command.usernameRequired()
                        && !command.usernameSignInEnabled()
                        && !command.passwordAtSignUpEnabled()
                        && !command.deviceTrustEnabled()
                        && !command.selfRegistrationRequiresApproval()
                        && !command.selfServiceEmailChangeEnabled()
                        && !command.codeAfterPasswordRequired()
                        && !command.totpEnabled()
                        && !command.passkeysEnabled()
                        && !command.passkeyOfferEnabled()));
  }

  @Test
  void aMethodThatIsNotOneOfTheThreeIsRefusedBeforeAnythingIsSaved() throws Exception {
    mockMvc
        .perform(post(path()).param("emailVerificationMethod", "CARRIER_PIGEON"))
        .andExpect(status().isBadRequest());

    verify(setPolicy, never()).handle(any());
  }

  @Test
  void anHtmxRequestGetsTheFragmentBack() throws Exception {
    mockMvc
        .perform(post(path()).param("accessCodeEnabled", "true").header("HX-Request", "true"))
        .andExpect(status().isOk())
        .andExpect(view().name(VIEW + " :: signInOptions"));
  }

  // Sign-up without a password needs the emailed code or link to let anyone in. The rule is shown,
  // and what the operator typed stays on the page instead of reverting to the stored values.
  @Test
  void theRuleThatASignUpWithoutAPasswordKeepsAPasswordlessMethodIsShownAndTheEditIsKept()
      throws Exception {
    doThrow(new PasswordOptionalRequiresPasswordlessSignInException())
        .when(setPolicy)
        .handle(any());

    final String body =
        mockMvc
            .perform(post(path()).param("deviceTrustEnabled", "true"))
            .andExpect(status().isOk())
            .andExpect(view().name(VIEW))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(body).contains("needs the one-time access code or the email link");
    assertThat(inputOf(body, "sign-in-options-device-trust")).contains("checked");
    assertThat(inputOf(body, "sign-in-options-password-sign-up")).doesNotContain("checked");
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

  private static String inputOf(final String body, final String id) {
    final int start = body.indexOf("id=\"" + id + "\"");
    assertThat(start).as("the input " + id).isGreaterThanOrEqualTo(0);
    return body.substring(start, body.indexOf("/>", start));
  }
}
