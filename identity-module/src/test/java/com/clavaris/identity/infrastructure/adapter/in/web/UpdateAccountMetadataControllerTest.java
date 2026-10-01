package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.identity.application.usecase.updateaccountmetadata.AccountNotFoundException;
import com.clavaris.identity.application.usecase.updateaccountmetadata.InvalidMetadataException;
import com.clavaris.identity.application.usecase.updateaccountmetadata.UpdateAccountMetadataCommand;
import com.clavaris.identity.application.usecase.updateaccountmetadata.UpdateAccountMetadataUseCase;
import com.clavaris.identity.domain.model.AccountId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Standalone MockMvc setup — same pattern as RejectAccountRegistrationControllerTest. */
class UpdateAccountMetadataControllerTest {

  private static final TestingAuthenticationToken ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private final UUID accountId = UUID.randomUUID();
  private UpdateAccountMetadataUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(UpdateAccountMetadataUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new UpdateAccountMetadataController(useCase)).build();
  }

  @Test
  void returns204AndPassesAllThreeTiersAndThePlatformClientActorThrough() throws Exception {
    mockMvc
        .perform(
            put("/api/v1/admin/accounts/" + accountId + "/metadata")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"publicMetadata\":\"{\\\"a\\\":1}\",\"privateMetadata\":\"{\\\"b\\\":2}\",\"unsafeMetadata\":\"{\\\"c\\\":3}\"}"))
        .andExpect(status().isNoContent());

    ArgumentCaptor<UpdateAccountMetadataCommand> command =
        ArgumentCaptor.forClass(UpdateAccountMetadataCommand.class);
    verify(useCase).handle(command.capture());
    assertThat(command.getValue().accountId()).isEqualTo(new AccountId(accountId));
    assertThat(command.getValue().publicMetadata()).isEqualTo("{\"a\":1}");
    assertThat(command.getValue().privateMetadata()).isEqualTo("{\"b\":2}");
    assertThat(command.getValue().unsafeMetadata()).isEqualTo("{\"c\":3}");
    assertThat(command.getValue().actor().id()).isEqualTo("test-platform-client");
  }

  @Test
  void returns404WhenTheAccountDoesNotExist() throws Exception {
    doThrow(new AccountNotFoundException(new AccountId(accountId))).when(useCase).handle(any());

    mockMvc
        .perform(
            put("/api/v1/admin/accounts/" + accountId + "/metadata")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"))
        .andExpect(status().isNotFound());
  }

  @Test
  void returns400WhenATierIsInvalid() throws Exception {
    doThrow(new InvalidMetadataException("publicMetadata", "is not valid JSON"))
        .when(useCase)
        .handle(any());

    mockMvc
        .perform(
            put("/api/v1/admin/accounts/" + accountId + "/metadata")
                .principal(ACTING_PLATFORM_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"publicMetadata\":\"not json\"}"))
        .andExpect(status().isBadRequest());
  }
}
