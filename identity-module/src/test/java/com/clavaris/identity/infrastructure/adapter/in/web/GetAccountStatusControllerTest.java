package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.identity.application.usecase.getaccountstatus.GetAccountStatusUseCase;
import com.clavaris.identity.domain.model.AccountStatus;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** BR-ID-35 (TD-FUT-081). Standalone MockMvc, same pattern as the sibling REST controller tests. */
class GetAccountStatusControllerTest {

  private final UUID accountId = UUID.randomUUID();
  private GetAccountStatusUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(GetAccountStatusUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new GetAccountStatusController(useCase)).build();
  }

  @ParameterizedTest
  @EnumSource(
      value = AccountStatus.class,
      names = {"ACTIVE", "SUSPENDED", "BANNED", "PENDING_APPROVAL", "REJECTED"})
  void returnsTheStatusAndTheAccountIdAndNothingElse(final AccountStatus accountStatus)
      throws Exception {
    when(useCase.handle(any())).thenReturn(Optional.of(accountStatus));

    mockMvc
        .perform(get("/api/v1/admin/accounts/" + accountId + "/status"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accountId").value(accountId.toString()))
        .andExpect(jsonPath("$.status").value(accountStatus.name()))
        // No personal data: exactly these two fields.
        .andExpect(jsonPath("$.length()").value(2));
  }

  @Test
  void aCachedStatusWouldDefeatTheReadSoItIsNeverCached() throws Exception {
    when(useCase.handle(any())).thenReturn(Optional.of(AccountStatus.ACTIVE));

    mockMvc
        .perform(get("/api/v1/admin/accounts/" + accountId + "/status"))
        .andExpect(header().string("Cache-Control", "no-store"));
  }

  @Test
  void returns404ForAnAccountThatDoesNotExist() throws Exception {
    when(useCase.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/admin/accounts/" + accountId + "/status"))
        .andExpect(status().isNotFound());
  }
}
