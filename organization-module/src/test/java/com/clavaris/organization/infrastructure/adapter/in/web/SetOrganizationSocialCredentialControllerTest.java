package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.organization.application.usecase.setorganizationsocialcredential.SetOrganizationSocialCredentialUseCase;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;

/**
 * CodeQL java/uncontrolled-arithmetic on the encryption of this secret: the request had no upper
 * bound on either field, so a body of any size reached the cipher. The limits are the dashboard
 * form's ({@code SocialConnectionForm}) and the column's ({@code client_id varchar(255)}).
 */
class SetOrganizationSocialCredentialControllerTest {

  private static final Principal ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private SetOrganizationSocialCredentialUseCase useCase;
  private MockMvc mockMvc;
  private String url;

  @BeforeEach
  void setUp() {
    useCase = mock(SetOrganizationSocialCredentialUseCase.class);
    LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
    validator.afterPropertiesSet();
    mockMvc =
        MockMvcBuilders.standaloneSetup(new SetOrganizationSocialCredentialController(useCase))
            .setValidator(validator)
            .build();
    url = "/api/v1/admin/organizations/" + UUID.randomUUID() + "/social-credentials/GOOGLE";
  }

  private org.springframework.test.web.servlet.ResultActions submit(
      final String clientId, final String clientSecret) throws Exception {
    return mockMvc.perform(
        put(url)
            .principal(ACTING_PLATFORM_CLIENT)
            .contentType(MediaType.APPLICATION_JSON)
            .content(
                "{\"clientId\":\"" + clientId + "\",\"clientSecret\":\"" + clientSecret + "\"}"));
  }

  @Test
  void rejectsASecretOverTheLimitWithoutCallingTheUseCase() throws Exception {
    submit("id", "s".repeat(1001)).andExpect(status().isBadRequest());

    verify(useCase, never()).handle(any());
  }

  @Test
  void rejectsAClientIdOverTheColumnWidthWithoutCallingTheUseCase() throws Exception {
    submit("i".repeat(256), "secret").andExpect(status().isBadRequest());

    verify(useCase, never()).handle(any());
  }

  @Test
  void stillRejectsABlankSecret() throws Exception {
    submit("id", " ").andExpect(status().isBadRequest());

    verify(useCase, never()).handle(any());
  }
}
