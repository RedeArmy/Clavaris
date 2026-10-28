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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.view;

import com.clavaris.identity.application.usecase.confirmemailchange.ConfirmEmailChangeUseCase;
import com.clavaris.identity.application.usecase.confirmemailchange.InvalidEmailChangeTokenException;
import com.clavaris.identity.application.usecase.registeraccount.EmailAlreadyRegisteredException;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

class AccountEmailConfirmationControllerTest {

  private static final UUID ORG = UUID.randomUUID();
  private static final String URL = "/o/" + ORG + "/account/email/confirm";

  private ConfirmEmailChangeUseCase confirmChange;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    confirmChange = mock(ConfirmEmailChangeUseCase.class);
    CurrentAccountResolver currentAccount = mock(CurrentAccountResolver.class);
    when(currentAccount.resolve(any())).thenReturn(Optional.of(AccountId.newId()));
    mockMvc =
        SelfServiceMvc.standalone(
            new AccountEmailConfirmationController(confirmChange, currentAccount));
  }

  @Test
  void openingTheLinkChangesNothingAndAsksForAClick() throws Exception {
    mockMvc
        .perform(get(URL).param("token", "the-token"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/account/email-confirmed"))
        .andExpect(model().attribute("pendingToken", "the-token"));

    verify(confirmChange, never()).handle(any());
  }

  @Test
  void aValidLinkConfirmsTheChange() throws Exception {
    mockMvc
        .perform(post(URL).param("token", "the-token"))
        .andExpect(status().isOk())
        .andExpect(view().name("identity/account/email-confirmed"))
        .andExpect(model().attribute("confirmed", true));

    verify(confirmChange).handle(any());
  }

  @Test
  void anInvalidLinkIsA400() throws Exception {
    doThrow(new InvalidEmailChangeTokenException()).when(confirmChange).handle(any());

    mockMvc
        .perform(post(URL).param("token", "bad"))
        .andExpect(status().isBadRequest())
        .andExpect(model().attribute("invalid", true));
  }

  @Test
  void anAddressTakenInTheMeantimeIsA409() throws Exception {
    doThrow(new EmailAlreadyRegisteredException(new OrganizationId(ORG)))
        .when(confirmChange)
        .handle(any());

    mockMvc
        .perform(post(URL).param("token", "t"))
        .andExpect(status().isConflict())
        .andExpect(model().attribute("taken", true));
  }
}
