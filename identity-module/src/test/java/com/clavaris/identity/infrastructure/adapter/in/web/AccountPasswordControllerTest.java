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
import com.clavaris.identity.application.usecase.changepassword.ChangePasswordUseCase;
import com.clavaris.identity.application.usecase.changepassword.IncorrectCurrentPasswordException;
import com.clavaris.identity.application.usecase.changepassword.NoPasswordToChangeException;
import com.clavaris.identity.application.usecase.registeraccount.BreachedPasswordException;
import com.clavaris.identity.application.usecase.registeraccount.WeakPasswordException;
import com.clavaris.identity.domain.model.AccountId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

class AccountPasswordControllerTest {

  private static final UUID ORG = UUID.randomUUID();
  private static final String URL = "/o/" + ORG + "/account/password";

  private ChangePasswordUseCase changePassword;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    changePassword = mock(ChangePasswordUseCase.class);
    CurrentAccountResolver currentAccount = mock(CurrentAccountResolver.class);
    when(currentAccount.resolve(any())).thenReturn(Optional.of(AccountId.newId()));
    mockMvc =
        SelfServiceMvc.standalone(new AccountPasswordController(changePassword, currentAccount));
  }

  private MockHttpServletRequestBuilder submit(final String current, final String confirm) {
    return post(URL)
        .param("currentPassword", current)
        .param("newPassword", "a-brand-new-passphrase-42")
        .param("confirmPassword", confirm);
  }

  @Test
  void showsTheForm() throws Exception {
    mockMvc
        .perform(get(URL))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/account/password"));
  }

  @Test
  void aValidChangeRedirectsToTheProfile() throws Exception {
    mockMvc
        .perform(submit("current-secret", "a-brand-new-passphrase-42"))
        .andExpect(redirectedUrl("/o/" + ORG + "/account/profile?passwordChanged"));

    verify(changePassword).handle(any());
  }

  @Test
  void aBlankCurrentPasswordIsAFieldErrorAndCallsNothing() throws Exception {
    mockMvc
        .perform(submit("", "a-brand-new-passphrase-42"))
        .andExpect(status().isOk())
        .andExpect(model().attributeHasFieldErrors("changePasswordForm", "currentPassword"));

    verify(changePassword, never()).handle(any());
  }

  @Test
  void aMismatchedConfirmationIsAFieldErrorAndCallsNothing() throws Exception {
    mockMvc
        .perform(submit("current-secret", "something-else-entirely-42"))
        .andExpect(status().isOk())
        .andExpect(model().attributeHasFieldErrors("changePasswordForm", "confirmPassword"));

    verify(changePassword, never()).handle(any());
  }

  @Test
  void aWrongCurrentPasswordIsAFieldError() throws Exception {
    doThrow(new IncorrectCurrentPasswordException()).when(changePassword).handle(any());

    mockMvc
        .perform(submit("wrong", "a-brand-new-passphrase-42"))
        .andExpect(status().isOk())
        .andExpect(model().attributeHasFieldErrors("changePasswordForm", "currentPassword"));
  }

  @Test
  void aWeakOrBreachedNewPasswordIsAFieldError() throws Exception {
    doThrow(new WeakPasswordException()).when(changePassword).handle(any());
    mockMvc
        .perform(submit("current-secret", "a-brand-new-passphrase-42"))
        .andExpect(model().attributeHasFieldErrors("changePasswordForm", "newPassword"));

    doThrow(new BreachedPasswordException()).when(changePassword).handle(any());
    mockMvc
        .perform(submit("current-secret", "a-brand-new-passphrase-42"))
        .andExpect(model().attributeHasFieldErrors("changePasswordForm", "newPassword"));
  }

  @Test
  void anAccountWithoutAPasswordIsToldSo() throws Exception {
    doThrow(new NoPasswordToChangeException()).when(changePassword).handle(any());

    mockMvc
        .perform(submit("x", "a-brand-new-passphrase-42"))
        .andExpect(status().isOk())
        .andExpect(model().attribute("noPassword", true));
  }

  @Test
  void aSaturatedArgonGateIsA503NotAWrongPassword() throws Exception {
    doThrow(new VerificationOverloadedException()).when(changePassword).handle(any());

    mockMvc
        .perform(submit("current-secret", "a-brand-new-passphrase-42"))
        .andExpect(status().isServiceUnavailable())
        .andExpect(model().attribute("serviceOverloadedError", true));
  }
}
