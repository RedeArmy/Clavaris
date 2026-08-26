package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.organization.application.usecase.deleteorganization.DeleteOrganizationCommand;
import com.clavaris.organization.application.usecase.deleteorganization.DeleteOrganizationUseCase;
import com.clavaris.organization.application.usecase.deleteorganization.OrganizationNotFoundException;
import java.security.Principal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

/**
 * Standalone MockMvc setup — same pattern/rationale as this package's own
 * CreateOrganizationControllerTest.
 */
class DeleteOrganizationControllerTest {

  private static final Principal ACTING_PLATFORM_CLIENT =
      new TestingAuthenticationToken("test-platform-client", null);

  private final UUID organizationId = UUID.randomUUID();
  private DeleteOrganizationUseCase useCase;
  private MockMvc mockMvc;

  @BeforeEach
  void setUp() {
    useCase = mock(DeleteOrganizationUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new DeleteOrganizationController(useCase)).build();
  }

  @Test
  void returns204AndPassesThePlatformClientActorThrough() throws Exception {
    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + ":delete")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNoContent());

    ArgumentCaptor<DeleteOrganizationCommand> command =
        ArgumentCaptor.forClass(DeleteOrganizationCommand.class);
    verify(useCase).handle(command.capture());
    assertThat(command.getValue().organizationId()).isEqualTo(organizationId);
    assertThat(command.getValue().actor().id()).isEqualTo("test-platform-client");
  }

  @Test
  void returns404WhenTheOrganizationDoesNotExist() throws Exception {
    doThrow(new OrganizationNotFoundException(organizationId)).when(useCase).handle(any());

    mockMvc
        .perform(
            post("/api/v1/admin/organizations/" + organizationId + ":delete")
                .principal(ACTING_PLATFORM_CLIENT))
        .andExpect(status().isNotFound());
  }
}
