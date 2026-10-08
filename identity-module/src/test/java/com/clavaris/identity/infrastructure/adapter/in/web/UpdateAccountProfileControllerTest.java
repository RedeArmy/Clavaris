package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
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
            .build();
  }

  private Account accountWith(final String firstName, final String lastName) {
    return Account.reconstitute(
        new AccountId(accountId),
        new OrganizationId(UUID.randomUUID()),
        new Email("ada@example.com"),
        Instant.now(),
        Instant.now(),
        AccountStatus.ACTIVE,
        null,
        null,
        null,
        firstName,
        lastName,
        null,
        null);
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
}
