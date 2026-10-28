package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
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

import com.clavaris.identity.application.usecase.authenticatewithpassword.VerificationOverloadedException;
import com.clavaris.identity.application.usecase.changepassword.IncorrectCurrentPasswordException;
import com.clavaris.identity.application.usecase.changepassword.RecentSignInRequiredException;
import com.clavaris.identity.application.usecase.requestemailchange.EmailChangeNotAllowedException;
import com.clavaris.identity.application.usecase.requestemailchange.RequestEmailChangeUseCase;
import com.clavaris.identity.application.usecase.requestemailchange.SelfServiceEmailChangePolicy;
import com.clavaris.identity.domain.model.AccountId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class AccountEmailControllerTest {

  private static final UUID ORG = UUID.randomUUID();
  private static final String URL = "/o/" + ORG + "/account/email";

  private RequestEmailChangeUseCase requestChange;
  private SelfServiceEmailChangePolicy policy;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    requestChange = mock(RequestEmailChangeUseCase.class);
    policy = mock(SelfServiceEmailChangePolicy.class);
    when(policy.isAllowed(any())).thenReturn(true);
    CurrentAccountResolver currentAccount = mock(CurrentAccountResolver.class);
    when(currentAccount.resolve(any())).thenReturn(Optional.of(AccountId.newId()));
    mockMvc =
        SelfServiceMvc.standalone(
            new AccountEmailController(requestChange, currentAccount, policy, 10));
  }

  private MockHttpServletRequestBuilder submit(final String newEmail, final String password) {
    return post(URL).param("newEmail", newEmail).param("currentPassword", password);
  }

  @Test
  void showsTheForm() throws Exception {
    mockMvc
        .perform(get(URL))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/account/email"));
  }

  @Test
  void aValidRequestRedirectsToTheProfile() throws Exception {
    mockMvc
        .perform(submit("new@example.com", "current-secret"))
        .andExpect(redirectedUrl("/o/" + ORG + "/account/profile?emailChangeRequested"));

    verify(requestChange).handle(any());
  }

  @Test
  void aMalformedAddressIsAFieldErrorAndCallsNothing() throws Exception {
    mockMvc
        .perform(submit("not-an-email", "current-secret"))
        .andExpect(status().isOk())
        .andExpect(model().attributeHasFieldErrors("changeEmailForm", "newEmail"));

    verify(requestChange, never()).handle(any());
  }

  @Test
  void anAddressTheDomainRejectsIsAFieldError() throws Exception {
    // Passes the form's bean validation but not the Email value object (no dot in the domain).
    mockMvc
        .perform(submit("someone@localhost", "current-secret"))
        .andExpect(status().isOk())
        .andExpect(model().attributeHasFieldErrors("changeEmailForm", "newEmail"));

    verify(requestChange, never()).handle(any());
  }

  @Test
  void aWrongPasswordIsAFieldError() throws Exception {
    doThrow(new IncorrectCurrentPasswordException()).when(requestChange).handle(any());

    mockMvc
        .perform(submit("new@example.com", "wrong"))
        .andExpect(model().attributeHasFieldErrors("changeEmailForm", "currentPassword"));
  }

  @Test
  void anAccountWithoutAPasswordAndAnOldSessionIsToldToSignInAgain() throws Exception {
    doThrow(new RecentSignInRequiredException()).when(requestChange).handle(any());

    mockMvc
        .perform(submit("new@example.com", ""))
        .andExpect(model().attribute("recentSignInRequired", true));
  }

  @Test
  void aSaturatedArgonGateIsA503() throws Exception {
    doThrow(new VerificationOverloadedException()).when(requestChange).handle(any());

    mockMvc
        .perform(submit("new@example.com", "current-secret"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(model().attribute("serviceOverloadedError", true));
  }

  @Test
  void theFormTellsAnOrganizationThatSwitchedEmailChangeOffTheyCannotUseIt() throws Exception {
    when(policy.isAllowed(any())).thenReturn(false);

    mockMvc.perform(get(URL)).andExpect(model().attribute("emailChangeDisabled", true));
  }

  @Test
  void aSubmissionTheOrganizationRefusesShowsTheSameNotice() throws Exception {
    doThrow(new EmailChangeNotAllowedException()).when(requestChange).handle(any());

    mockMvc
        .perform(submit("new@example.com", "current-secret"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("emailChangeDisabled", true));
  }
}
