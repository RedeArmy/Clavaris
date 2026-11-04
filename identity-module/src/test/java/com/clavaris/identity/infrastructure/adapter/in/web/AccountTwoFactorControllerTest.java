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

import com.clavaris.identity.application.usecase.codelockout.CodeTemporarilyLockedException;
import com.clavaris.identity.application.usecase.confirmtotpenrollment.ConfirmTotpEnrollmentUseCase;
import com.clavaris.identity.application.usecase.disabletotp.DisableTotpUseCase;
import com.clavaris.identity.application.usecase.gettotpstatus.GetTotpStatusUseCase;
import com.clavaris.identity.application.usecase.regeneraterecoverycodes.RegenerateRecoveryCodesUseCase;
import com.clavaris.identity.application.usecase.starttotpenrollment.StartTotpEnrollmentUseCase;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpAlreadyEnrolledException;
import com.clavaris.identity.application.usecase.starttotpenrollment.TotpEnrollmentStart;
import com.clavaris.identity.application.usecase.verifytotpchallenge.InvalidTotpCodeException;
import com.clavaris.identity.domain.model.AccountId;
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

class AccountTwoFactorControllerTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();
  private static final String BASE = "/o/{organizationId}/account/two-factor";

  private final AccountId accountId = AccountId.newId();
  private GetTotpStatusUseCase status;
  private StartTotpEnrollmentUseCase start;
  private ConfirmTotpEnrollmentUseCase confirm;
  private DisableTotpUseCase disable;
  private RegenerateRecoveryCodesUseCase regenerate;
  private RequireRecentAuthentication recent;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    status = mock(GetTotpStatusUseCase.class);
    start = mock(StartTotpEnrollmentUseCase.class);
    confirm = mock(ConfirmTotpEnrollmentUseCase.class);
    disable = mock(DisableTotpUseCase.class);
    regenerate = mock(RegenerateRecoveryCodesUseCase.class);
    recent = mock(RequireRecentAuthentication.class);
    final CurrentAccountResolver currentAccount = mock(CurrentAccountResolver.class);
    when(currentAccount.resolve(any())).thenReturn(Optional.of(accountId));
    when(recent.isStale(ORGANIZATION_ID)).thenReturn(false);
    when(status.handle(accountId)).thenReturn(new GetTotpStatusUseCase.TotpStatus(true, false, 0));
    when(start.handle(any())).thenReturn(new TotpEnrollmentStart("ABCD EFGH", "otpauth://totp/x"));

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
        MockMvcBuilders.standaloneSetup(
                new AccountTwoFactorController(
                    status, start, confirm, disable, regenerate, currentAccount, recent))
            .setViewResolvers(viewResolver)
            .build();
  }

  @Test
  void thePageShowsAvailabilityAndEnrolment() throws Exception {
    when(status.handle(accountId)).thenReturn(new GetTotpStatusUseCase.TotpStatus(true, true, 7));

    mockMvc
        .perform(get(BASE, ORGANIZATION_ID))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/account/two-factor"))
        .andExpect(model().attribute("enrolled", true))
        .andExpect(model().attribute("unusedRecoveryCodes", 7L));
  }

  @Test
  void settingUpShowsTheSecretOnTheAnswerToAPost() throws Exception {
    mockMvc
        .perform(post(BASE + "/setup", ORGANIZATION_ID))
        .andExpect(view().name("identity/account/two-factor-setup"))
        .andExpect(model().attribute("secret", "ABCD EFGH"));
  }

  @Test
  void settingUpWhenAlreadyEnrolledFallsBackToThePage() throws Exception {
    when(start.handle(any())).thenThrow(new TotpAlreadyEnrolledException());

    mockMvc
        .perform(post(BASE + "/setup", ORGANIZATION_ID))
        .andExpect(view().name("identity/account/two-factor"));
  }

  @Test
  void aStaleSignInIsSentToReverifyBeforeAnythingChanges() throws Exception {
    when(recent.isStale(ORGANIZATION_ID)).thenReturn(true);

    mockMvc
        .perform(post(BASE + "/setup", ORGANIZATION_ID))
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/login"));
    mockMvc
        .perform(post(BASE + "/disable", ORGANIZATION_ID).param("code", "123456"))
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/login"));

    verify(start, never()).handle(any());
    verify(disable, never()).handle(any());
  }

  @Test
  void confirmingShowsTheRecoveryCodesOnceInTheAnswer() throws Exception {
    when(confirm.handle(any())).thenReturn(List.of("AAAA-BBBB-CCCC-DDDD"));

    mockMvc
        .perform(post(BASE + "/confirm", ORGANIZATION_ID).param("code", "123456"))
        .andExpect(view().name("identity/account/two-factor-recovery-codes"))
        .andExpect(model().attribute("recoveryCodes", List.of("AAAA-BBBB-CCCC-DDDD")));
  }

  @Test
  void aWrongConfirmingCodeShowsANewSetupWithAnError() throws Exception {
    when(confirm.handle(any())).thenThrow(new InvalidTotpCodeException());

    mockMvc
        .perform(post(BASE + "/confirm", ORGANIZATION_ID).param("code", "000000"))
        .andExpect(view().name("identity/account/two-factor-setup"))
        .andExpect(model().attribute("codeError", true));
  }

  @Test
  void removingWithARightCodeReturnsToThePage() throws Exception {
    mockMvc
        .perform(post(BASE + "/disable", ORGANIZATION_ID).param("code", "123456"))
        .andExpect(redirectedUrl("/o/" + ORGANIZATION_ID + "/account/two-factor"));

    verify(disable).handle(any());
  }

  @Test
  void removingWithAWrongCodeKeepsItAndSaysSo() throws Exception {
    org.mockito.Mockito.doThrow(new InvalidTotpCodeException()).when(disable).handle(any());

    mockMvc
        .perform(post(BASE + "/disable", ORGANIZATION_ID).param("code", "000000"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("codeError", true));
  }

  @Test
  void aPausedMethodIsReportedWhenRemovingOrMakingNewCodes() throws Exception {
    org.mockito.Mockito.doThrow(new CodeTemporarilyLockedException()).when(disable).handle(any());
    when(regenerate.handle(any())).thenThrow(new CodeTemporarilyLockedException());

    mockMvc
        .perform(post(BASE + "/disable", ORGANIZATION_ID).param("code", "1"))
        .andExpect(model().attribute("codeLocked", true));
    mockMvc
        .perform(post(BASE + "/recovery-codes", ORGANIZATION_ID).param("code", "1"))
        .andExpect(model().attribute("codeLocked", true));
  }

  @Test
  void newRecoveryCodesAreShownOnceAndAWrongCodeShowsTheErrorOnThePage() throws Exception {
    when(regenerate.handle(any())).thenReturn(List.of("EEEE-FFFF-GGGG-HHHH"));
    mockMvc
        .perform(post(BASE + "/recovery-codes", ORGANIZATION_ID).param("code", "123456"))
        .andExpect(view().name("identity/account/two-factor-recovery-codes"));

    when(regenerate.handle(any())).thenThrow(new InvalidTotpCodeException());
    mockMvc
        .perform(post(BASE + "/recovery-codes", ORGANIZATION_ID).param("code", "000000"))
        .andExpect(view().name("identity/account/two-factor"))
        .andExpect(model().attribute("codeError", true));
  }

  @Test
  void theRecoveryCodesPageIsNotReachableByGet() throws Exception {
    mockMvc
        .perform(get(BASE + "/confirm", ORGANIZATION_ID))
        .andExpect(status().isMethodNotAllowed());
  }
}
