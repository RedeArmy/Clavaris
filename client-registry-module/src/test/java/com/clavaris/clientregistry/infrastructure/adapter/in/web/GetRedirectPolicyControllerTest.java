package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.clientregistry.application.usecase.getredirectpolicyforclient.GetRedirectPolicyForClientUseCase;
import com.clavaris.clientregistry.domain.model.RedirectPolicy;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GetRedirectPolicyControllerTest {

  private GetRedirectPolicyForClientUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(GetRedirectPolicyForClientUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new GetRedirectPolicyController(useCase)).build();
  }

  @Test
  void returns200WithUnconfiguredDefaultsWhenNoPolicyHasEverBeenSet() throws Exception {
    UUID organizationId = UUID.randomUUID();
    UUID oauthClientId = UUID.randomUUID();
    when(useCase.handle(oauthClientId)).thenReturn(RedirectPolicy.unconfigured(oauthClientId));

    mockMvc
        .perform(
            get(
                "/api/v1/admin/organizations/"
                    + organizationId
                    + "/clients/"
                    + oauthClientId
                    + "/redirect-policy"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.oauthClientId").value(oauthClientId.toString()))
        .andExpect(jsonPath("$.fallbackSignInRedirectUrl").value(nullValue()));
  }
}
