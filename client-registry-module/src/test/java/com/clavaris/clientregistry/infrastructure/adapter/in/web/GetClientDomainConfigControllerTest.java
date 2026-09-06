package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.clientregistry.application.usecase.getclientdomainconfig.GetClientDomainConfigUseCase;
import com.clavaris.clientregistry.domain.model.ClientDomainConfig;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GetClientDomainConfigControllerTest {

  private GetClientDomainConfigUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(GetClientDomainConfigUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new GetClientDomainConfigController(useCase)).build();
  }

  @Test
  void returns200WithUnconfiguredSharedModeDefaultsWhenNoDomainHasEverBeenRequested()
      throws Exception {
    UUID organizationId = UUID.randomUUID();
    UUID oauthClientId = UUID.randomUUID();
    when(useCase.handle(oauthClientId)).thenReturn(ClientDomainConfig.unconfigured(oauthClientId));

    mockMvc
        .perform(
            get(
                "/api/v1/admin/organizations/"
                    + organizationId
                    + "/clients/"
                    + oauthClientId
                    + "/domain-config"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.oauthClientId").value(oauthClientId.toString()))
        .andExpect(jsonPath("$.mode").value(nullValue()));
  }
}
