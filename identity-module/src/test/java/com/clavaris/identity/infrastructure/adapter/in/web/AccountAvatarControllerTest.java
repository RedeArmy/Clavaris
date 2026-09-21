package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.identity.application.usecase.getaccountavatar.AccountAvatarResult;
import com.clavaris.identity.application.usecase.getaccountavatar.GetAccountAvatarUseCase;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class AccountAvatarControllerTest {

  private static final UUID ORGANIZATION_ID = UUID.randomUUID();
  private static final UUID ACCOUNT_ID = UUID.randomUUID();

  private GetAccountAvatarUseCase getAvatar;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    getAvatar = mock(GetAccountAvatarUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new AccountAvatarController(getAvatar)).build();
  }

  @Test
  void returnsNotFoundWhenTheUseCaseResolvesNothing() throws Exception {
    when(getAvatar.handle(any())).thenReturn(Optional.empty());

    mockMvc
        .perform(get("/o/{organizationId}/avatars/{accountId}", ORGANIZATION_ID, ACCOUNT_ID))
        .andExpect(status().isNotFound());
  }

  @Test
  void redirectsToAnExternalProviderUrl() throws Exception {
    when(getAvatar.handle(any()))
        .thenReturn(Optional.of(new AccountAvatarResult.Redirect("https://example.com/a.png")));

    mockMvc
        .perform(get("/o/{organizationId}/avatars/{accountId}", ORGANIZATION_ID, ACCOUNT_ID))
        .andExpect(status().isFound())
        .andExpect(header().string("Location", "https://example.com/a.png"));
  }

  @Test
  void servesStoredContentWithACacheControlHeader() throws Exception {
    when(getAvatar.handle(any()))
        .thenReturn(
            Optional.of(new AccountAvatarResult.Content(new byte[] {1, 2, 3}, "image/png")));

    mockMvc
        .perform(get("/o/{organizationId}/avatars/{accountId}", ORGANIZATION_ID, ACCOUNT_ID))
        .andExpect(status().isOk())
        .andExpect(content().contentType("image/png"))
        .andExpect(content().bytes(new byte[] {1, 2, 3}))
        .andExpect(
            header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age")));
  }
}
