package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.identity.application.usecase.deleteaccount.AccountNotFoundException;
import com.clavaris.identity.application.usecase.deleteaccount.DeleteAccountCommand;
import com.clavaris.identity.application.usecase.deleteaccount.DeleteAccountUseCase;
import com.clavaris.identity.domain.model.AccountId;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc setup — same pattern/rationale as organization-module's own
 * CreateOrganizationControllerTest ({@code @WebMvcTest} doesn't exist in this Spring Boot version).
 * {@code .principal(...)}, not {@code SecurityContextHolder}, is what a standalone MockMvc
 * instance's built-in {@code Authentication} argument resolver actually reads — same convention as
 * that test's own {@code ACTING_PLATFORM_CLIENT}.
 */
class DeleteAccountControllerTest {

  private static final TestingAuthenticationToken ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private final UUID accountId = UUID.randomUUID();
  private DeleteAccountUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(DeleteAccountUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new DeleteAccountController(useCase)).build();
  }

  @Test
  void returns204AndPassesThePlatformClientActorThrough() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/accounts/" + accountId + ":delete")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNoContent());

    ArgumentCaptor<DeleteAccountCommand> command =
        ArgumentCaptor.forClass(DeleteAccountCommand.class);
    verify(useCase).handle(command.capture());
    assertThat(command.getValue().accountId()).isEqualTo(new AccountId(accountId));
    assertThat(command.getValue().actor().id()).isEqualTo("test-platform-client");
  }

  @Test
  void returns404WhenTheAccountDoesNotExist() throws Exception {
    doThrow(new AccountNotFoundException(new AccountId(accountId))).when(useCase).handle(any());

    mockMvc
        .perform(
            post("/api/v1/admin/accounts/" + accountId + ":delete")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNotFound());
  }
}
