package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.clavaris.common.domain.model.KeysetCursor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged.ListWorkspacesForOrganizationPagedQuery;
import com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged.ListWorkspacesForOrganizationPagedUseCase;
import com.clavaris.organization.domain.model.Workspace;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ListWorkspacesControllerTest {

  private ListWorkspacesForOrganizationPagedUseCase useCase;
  private MockMvc mockMvc;
  private final UUID organizationId = UUID.randomUUID();
  private final String path = "/api/v1/admin/organizations/" + organizationId + "/workspaces";

  @BeforeEach
  void setUp() {
    useCase = mock(ListWorkspacesForOrganizationPagedUseCase.class);
    mockMvc = MockMvcBuilders.standaloneSetup(new ListWorkspacesController(useCase)).build();
  }

  private KeysetPage<Workspace> page(final boolean hasNext, final Workspace... content) {
    final KeysetCursor end =
        new KeysetCursor(Instant.parse("2026-10-26T10:00:00Z"), UUID.randomUUID());
    return new KeysetPage<>(List.of(content), null, end, hasNext, false);
  }

  @Test
  void returns200WithABareArrayAndNoLinkOnTheLastPage() throws Exception {
    Workspace workspace = Workspace.register(organizationId, "Engineering");
    when(useCase.handle(any())).thenReturn(page(false, workspace));

    mockMvc
        .perform(get(path))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].id").value(workspace.id().toString()))
        .andExpect(jsonPath("$[0].name").value("Engineering"))
        .andExpect(header().doesNotExist("Link"));
  }

  @Test
  void pointsAtTheNextPageThroughALinkHeaderWhenMoreRemain() throws Exception {
    KeysetPage<Workspace> first = page(true, Workspace.register(organizationId, "Engineering"));
    when(useCase.handle(any())).thenReturn(first);

    mockMvc
        .perform(get(path).param("limit", "25"))
        .andExpect(status().isOk())
        .andExpect(
            header()
                .string(
                    "Link",
                    "<"
                        + path
                        + "?after="
                        + first.endCursor().encode()
                        + "&limit=25>; rel=\"next\""));
  }

  @Test
  void defaultsToTheMaximumPageSizeAndPassesTheCursorThrough() throws Exception {
    KeysetCursor cursor =
        new KeysetCursor(Instant.parse("2026-10-26T09:00:00Z"), UUID.randomUUID());
    when(useCase.handle(any())).thenReturn(page(false));

    mockMvc.perform(get(path).param("after", cursor.encode())).andExpect(status().isOk());

    ArgumentCaptor<ListWorkspacesForOrganizationPagedQuery> query =
        ArgumentCaptor.forClass(ListWorkspacesForOrganizationPagedQuery.class);
    verify(useCase).handle(query.capture());
    assertThat(query.getValue().organizationId()).isEqualTo(organizationId);
    assertThat(query.getValue().pageRequest().size()).isEqualTo(KeysetPageRequest.MAX_SIZE);
    assertThat(query.getValue().pageRequest().after()).isEqualTo(cursor);
  }

  @Test
  void rejectsAMalformedCursorWith400() throws Exception {
    mockMvc.perform(get(path).param("after", "not-a-cursor")).andExpect(status().isBadRequest());
  }

  @Test
  void rejectsALimitOutsideTheAllowedRangeWith400() throws Exception {
    mockMvc.perform(get(path).param("limit", "0")).andExpect(status().isBadRequest());
    mockMvc
        .perform(get(path).param("limit", String.valueOf(KeysetPageRequest.MAX_SIZE + 1)))
        .andExpect(status().isBadRequest());
  }
}
