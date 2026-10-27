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
import com.clavaris.identity.application.usecase.changepassword.NoPasswordToChangeException;
import com.clavaris.identity.application.usecase.confirmemailchange.ConfirmEmailChangeUseCase;
import com.clavaris.identity.application.usecase.confirmemailchange.InvalidEmailChangeTokenException;
import com.clavaris.identity.application.usecase.registeraccount.EmailAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.requestemailchange.RequestEmailChangeUseCase;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
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
  private ConfirmEmailChangeUseCase confirmChange;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    requestChange = mock(RequestEmailChangeUseCase.class);
    confirmChange = mock(ConfirmEmailChangeUseCase.class);
    CurrentAccountResolver currentAccount = mock(CurrentAccountResolver.class);
    when(currentAccount.resolve(any())).thenReturn(Optional.of(AccountId.newId()));
    mockMvc =
        SelfServiceMvc.standalone(
            new AccountEmailController(requestChange, confirmChange, currentAccount));
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
  void anAccountWithoutAPasswordIsToldSo() throws Exception {
    doThrow(new NoPasswordToChangeException()).when(requestChange).handle(any());

    mockMvc
        .perform(submit("new@example.com", "x"))
        .andExpect(model().attribute("noPassword", true));
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
  void aValidLinkConfirmsTheChange() throws Exception {
    mockMvc
        .perform(get(URL + "/confirm").param("token", "the-token"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/account/email-confirmed"))
        .andExpect(model().attribute("confirmed", true));

    verify(confirmChange).handle(any());
  }

  @Test
  void anInvalidLinkIsA400() throws Exception {
    doThrow(new InvalidEmailChangeTokenException()).when(confirmChange).handle(any());

    mockMvc
        .perform(get(URL + "/confirm").param("token", "bad"))
        .andExpect(status().isBadRequest())
        .andExpect(model().attribute("invalid", true));
  }

  @Test
  void anAddressTakenInTheMeantimeIsA409() throws Exception {
    doThrow(new EmailAlreadyRegisteredException(new OrganizationId(ORG)))
        .when(confirmChange)
        .handle(any());

    mockMvc
        .perform(get(URL + "/confirm").param("token", "t"))
        .andExpect(status().isConflict())
        .andExpect(model().attribute("taken", true));
  }
}
