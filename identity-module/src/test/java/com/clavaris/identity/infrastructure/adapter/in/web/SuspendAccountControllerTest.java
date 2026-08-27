package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.identity.application.usecase.suspendaccount.AccountNotFoundException;
import com.clavaris.identity.application.usecase.suspendaccount.SuspendAccountCommand;
import com.clavaris.identity.application.usecase.suspendaccount.SuspendAccountUseCase;
import com.clavaris.identity.domain.model.AccountId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** Standalone MockMvc setup — same pattern as DeleteAccountControllerTest. */
class SuspendAccountControllerTest {

  private static final TestingAuthenticationToken ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private final UUID accountId = UUID.randomUUID();
  private SuspendAccountUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(SuspendAccountUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new SuspendAccountController(useCase)).build();
  }

  @Test
  void returns204AndPassesThePlatformClientActorThrough() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/accounts/" + accountId + ":suspend")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNoContent());

    ArgumentCaptor<SuspendAccountCommand> command =
        ArgumentCaptor.forClass(SuspendAccountCommand.class);
    verify(useCase).handle(command.capture());
    assertThat(command.getValue().accountId()).isEqualTo(new AccountId(accountId));
    assertThat(command.getValue().actor().id()).isEqualTo("test-platform-client");
  }

  @Test
  void returns404WhenTheAccountDoesNotExist() throws Exception {
    doThrow(new AccountNotFoundException(new AccountId(accountId))).when(useCase).handle(any());

    mockMvc
        .perform(
            post("/api/v1/admin/accounts/" + accountId + ":suspend")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNotFound());
  }
}
