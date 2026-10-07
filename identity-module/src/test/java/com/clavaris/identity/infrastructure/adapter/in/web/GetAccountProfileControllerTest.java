package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.identity.application.usecase.getaccountprofile.GetAccountProfileUseCase;
import com.clavaris.identity.domain.model.Account;
import com.clavaris.identity.domain.model.AccountId;
import com.clavaris.identity.domain.model.AccountStatus;
import com.clavaris.identity.domain.model.Email;
import com.clavaris.identity.domain.model.OrganizationId;
import com.clavaris.identity.domain.model.Username;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/** TD-FUT-041. Standalone MockMvc setup — same pattern as sibling REST controller tests. */
class GetAccountProfileControllerTest {

  private final UUID accountId = UUID.randomUUID();
  private GetAccountProfileUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(GetAccountProfileUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new GetAccountProfileController(useCase)).build();
  }

  @Test
  void returns200WithTheAccountsProfileFields() throws Exception {
    Account account =
        Account.reconstitute(
            new AccountId(accountId),
            new OrganizationId(UUID.randomUUID()),
            new Email("ada@example.com"),
            Instant.now(),
            Instant.now(),
            AccountStatus.ACTIVE,
            null,
            new Username("ada"),
            null,
            "Ada",
            "Lovelace",
            "+502 5555-0100",
            null);
    when(useCase.handle(any())).thenReturn(Optional.of(account));

    mockMvc
        .perform(get("/api/v1/admin/accounts/" + accountId + "/profile"))
        .andExpect(status().isOk())
        .andExpect(content().string(Matchers.containsString("\"firstName\":\"Ada\"")))
        .andExpect(content().string(Matchers.containsString("\"username\":\"ada\"")))
        .andExpect(content().string(Matchers.containsString("\"phoneNumber\":\"+502 5555-0100\"")));
  }

  @Test
  void returns404WhenTheAccountDoesNotExist() throws Exception {
    when(useCase.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/api/v1/admin/accounts/" + accountId + "/profile"))
        .andExpect(status().isNotFound());
  }
}
