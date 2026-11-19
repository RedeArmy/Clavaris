package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.identity.application.usecase.getaccountprofile.GetAccountProfileUseCase;
import com.clavaris.identity.application.usecase.registeraccount.UsernameAlreadyRegisteredException;
import com.clavaris.identity.application.usecase.updateaccountprofile.AccountNotFoundException;
import com.clavaris.identity.application.usecase.updateaccountprofile.UpdateAccountProfileCommand;
import com.clavaris.identity.application.usecase.updateaccountprofile.UpdateAccountProfileUseCase;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * TD-FUT-041. Standalone MockMvc setup — same pattern as {@link
 * UpdateAccountMetadataControllerTest}.
 */
class UpdateAccountProfileControllerTest {

  private static final TestingAuthenticationToken ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private final UUID accountId = UUID.randomUUID();
  private GetAccountProfileUseCase getProfile;
  private UpdateAccountProfileUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    getProfile = mock(GetAccountProfileUseCase.class);
    useCase = mock(UpdateAccountProfileUseCase.class);
    mockMvc =
        MockMvcBuilders.standaloneSetup(new UpdateAccountProfileController(getProfile, useCase))
            .setValidator(
                new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean())
            .build();
  }

  private Account accountWith(final String firstName, final String lastName) {
    final Account account =
        Account.reconstitute(
            new AccountId(accountId),
            new OrganizationId(UUID.randomUUID()),
            new Email("ada@example.com"),
            Instant.now(),
            Instant.now(),
            AccountStatus.ACTIVE,
            null,
            null,
            null);
    account.updateProfile(firstName, lastName);
    return account;
  }

  @Test
  void returns204AndPassesEveryFieldAndThePlatformClientActorThrough() throws Exception {
    when(getProfile.handle(any())).thenReturn(Optional.of(accountWith("OldFirst", "OldLast")));

    mockMvc
        .perform(
            put("/api/v1/admin/accounts/" + accountId + "/profile")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"firstName\":\"Ada\",\"lastName\":\"Lovelace\",\"username\":\"ada\",\"phoneNumber\":\"+502 5555-0100\"}"))
        .andExpect(status().isNoContent());

    ArgumentCaptor<UpdateAccountProfileCommand> command =
        ArgumentCaptor.forClass(UpdateAccountProfileCommand.class);
    verify(useCase).handle(command.capture());
    assertThat(command.getValue().accountId()).isEqualTo(new AccountId(accountId));
    assertThat(command.getValue().firstName()).isEqualTo("Ada");
    assertThat(command.getValue().lastName()).isEqualTo("Lovelace");
    assertThat(command.getValue().username()).isEqualTo("ada");
    assertThat(command.getValue().phoneNumber()).isEqualTo("+502 5555-0100");
    assertThat(command.getValue().actor().id()).isEqualTo("test-platform-client");
  }

  // Validation finding, same day: a consumer sending only {"username": "x"} must not silently
  // wipe an existing firstName/lastName — see UpdateAccountProfileController's own Javadoc for
  // the full "no Optional-typed parameters, always-overwrite domain method" rationale this guards
  // against.
  @Test
  void preservesTheExistingNameWhenOmittedFromAPartialUpdate() throws Exception {
    when(getProfile.handle(any())).thenReturn(Optional.of(accountWith("Ada", "Lovelace")));

    mockMvc
        .perform(
            put("/api/v1/admin/accounts/" + accountId + "/profile")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"ada-lovelace\"}"))
        .andExpect(status().isNoContent());

    ArgumentCaptor<UpdateAccountProfileCommand> command =
        ArgumentCaptor.forClass(UpdateAccountProfileCommand.class);
    verify(useCase).handle(command.capture());
    assertThat(command.getValue().firstName()).isEqualTo("Ada");
    assertThat(command.getValue().lastName()).isEqualTo("Lovelace");
    assertThat(command.getValue().username()).isEqualTo("ada-lovelace");
  }

  @Test
  void returns404WhenTheAccountDoesNotExist() throws Exception {
    when(getProfile.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(
            put("/api/v1/admin/accounts/" + accountId + "/profile")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNotFound());
  }

  // TOCTOU: the read above found the Account, but it was deleted before the write landed.
  @Test
  void returns404WhenTheAccountIsDeletedBetweenTheReadAndTheWrite() throws Exception {
    when(getProfile.handle(any())).thenReturn(Optional.of(accountWith("Ada", "Lovelace")));
    doThrow(new AccountNotFoundException(new AccountId(accountId))).when(useCase).handle(any());

    mockMvc
        .perform(
            put("/api/v1/admin/accounts/" + accountId + "/profile")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void returns409WhenTheUsernameIsAlreadyTaken() throws Exception {
    when(getProfile.handle(any())).thenReturn(Optional.of(accountWith("Ada", "Lovelace")));
    doThrow(new UsernameAlreadyRegisteredException(new OrganizationId(UUID.randomUUID())))
        .when(useCase)
        .handle(any());

    mockMvc
        .perform(
            put("/api/v1/admin/accounts/" + accountId + "/profile")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"ada\"}"))
        .andExpect(status().isConflict());
  }

  // TD-ARCH-060 / BR-ADMIN-03: bad input is a 400 and reaches no use case. These were 500s.
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "{\"username\":\"\"}",
        "{\"username\":\"ab\"}",
        "{\"username\":\"!!bad name\"}",
        "{\"username\":\"a@b\"}",
        "{\"username\":\"xxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx\"}",
        "{\"phoneNumber\":\"not a phone\"}",
        "{\"phoneNumber\":\"\"}",
        "{\"phoneNumber\":\"+502 5555-0100 5555-0100 5555-0100\"}"
      })
  void returns400ForAValueThatCanNeverBeStored(final String body) throws Exception {
    mockMvc
        .perform(
            put("/api/v1/admin/accounts/" + accountId + "/profile")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(useCase);
  }

  @Test
  void returns400ForANameLongerThanItsColumn() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/admin/accounts/" + accountId + "/profile")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"" + "x".repeat(256) + "\"}"))
        .andExpect(status().isBadRequest());
    mockMvc
        .perform(
            put("/api/v1/admin/accounts/" + accountId + "/profile")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"lastName\":\"" + "x".repeat(256) + "\"}"))
        .andExpect(status().isBadRequest());

    verifyNoInteractions(useCase);
  }

  // The bounds are inclusive and the shapes that real callers send stay accepted.
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "{\"username\":\"abc\"}",
        "{\"username\":\"  Ada_Lovelace-1  \"}",
        "{\"phoneNumber\":\"+502 5555-0100\"}",
        "{\"phoneNumber\":\"(502) 5555.0100\"}",
        "{\"phoneNumber\":\"55550100\"}",
        "{\"firstName\":\"\"}"
      })
  void stillAcceptsTheValuesRealCallersSend(final String body) throws Exception {
    when(getProfile.handle(any())).thenReturn(Optional.of(accountWith("Ada", "Lovelace")));

    mockMvc
        .perform(
            put("/api/v1/admin/accounts/" + accountId + "/profile")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isNoContent());
  }

  @Test
  void acceptsANameAtExactlyTwoHundredFiftyFiveCharacters() throws Exception {
    when(getProfile.handle(any())).thenReturn(Optional.of(accountWith("Ada", "Lovelace")));

    mockMvc
        .perform(
            put("/api/v1/admin/accounts/" + accountId + "/profile")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"firstName\":\"" + "x".repeat(255) + "\"}"))
        .andExpect(status().isNoContent());
  }
}
