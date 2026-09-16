package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.clientregistry.application.usecase.getclientbranding.GetClientBrandingUseCase;
import com.clavaris.clientregistry.application.usecase.getclientbranding.OAuthClientNotFoundException;
import com.clavaris.clientregistry.domain.model.ClientBranding;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class GetClientBrandingControllerTest {

  private GetClientBrandingUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(GetClientBrandingUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new GetClientBrandingController(useCase)).build();
  }

  @Test
  void returns200WithUnconfiguredDefaultsWhenNoBrandingHasEverBeenSet() throws Exception {
    UUID organizationId = UUID.randomUUID();
    UUID oauthClientId = UUID.randomUUID();
    when(useCase.handle(organizationId, oauthClientId))
        .thenReturn(ClientBranding.unconfigured(oauthClientId));

    mockMvc
        .perform(
            get(
                "/api/v1/admin/organizations/"
                    + organizationId
                    + "/clients/"
                    + oauthClientId
                    + "/branding"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.oauthClientId").value(oauthClientId.toString()))
        .andExpect(jsonPath("$.logoUrl").value(nullValue()));
  }

  // SDE-III review, 2026-09-15: same 404-on-cross-tenant-or-missing-client mapping as
  // SetClientBrandingController's own identical test — see GetClientBrandingService's own Javadoc
  // for the ownership check this now enforces.
  @Test
  void returns404WhenTheOAuthClientDoesNotBelongToThisOrganization() throws Exception {
    UUID organizationId = UUID.randomUUID();
    UUID oauthClientId = UUID.randomUUID();
    when(useCase.handle(any(), any())).thenThrow(new OAuthClientNotFoundException(oauthClientId));

    mockMvc
        .perform(
            get(
                "/api/v1/admin/organizations/"
                    + organizationId
                    + "/clients/"
                    + oauthClientId
                    + "/branding"))
        .andExpect(status().isNotFound());
  }
}
