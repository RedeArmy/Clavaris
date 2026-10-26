package com.clavaris.organization.application.usecase.listworkspacesfororganization;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.clavaris.common.domain.model.KeysetCursor;
import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.organization.application.usecase.createworkspace.WorkspaceRepository;
import com.clavaris.organization.domain.model.Workspace;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class ListWorkspacesForOrganizationServiceTest {

  private final WorkspaceRepository workspaces = mock(WorkspaceRepository.class);
  private final UUID organizationId = UUID.randomUUID();
  private final ListWorkspacesForOrganizationService service =
      new ListWorkspacesForOrganizationService(workspaces);

  private KeysetPage<Workspace> page(final List<Workspace> content, final boolean hasNext) {
    return new KeysetPage<>(
        content, null, new KeysetCursor(Instant.now(), UUID.randomUUID()), hasNext, false);
  }

  private List<Workspace> workspaces(final int count) {
    final List<Workspace> list = new ArrayList<>();
    for (int i = 0; i < count; i++) {
      list.add(Workspace.register(organizationId, "Workspace " + i));
    }
    return list;
  }

  @Test
  void returnsTheOnlyPageWhenThereIsNoNextOne() {
    final Workspace workspace = Workspace.register(organizationId, "Engineering");
    when(workspaces.findKeysetPageByOrganizationId(eq(organizationId), any()))
        .thenReturn(page(List.of(workspace), false));

    assertThat(service.handle(new ListWorkspacesForOrganizationQuery(organizationId)))
        .containsExactly(workspace);
  }

  @Test
  void followsTheCursorAcrossPagesAndKeepsTheOrder() {
    final List<Workspace> first = workspaces(KeysetPageRequest.MAX_SIZE);
    final List<Workspace> second = workspaces(3);
    final KeysetPage<Workspace> firstPage = page(first, true);
    when(workspaces.findKeysetPageByOrganizationId(eq(organizationId), any()))
        .thenReturn(firstPage, page(second, false));

    final List<Workspace> result =
        service.handle(new ListWorkspacesForOrganizationQuery(organizationId));

    assertThat(result).hasSize(KeysetPageRequest.MAX_SIZE + 3);
    assertThat(result.subList(0, KeysetPageRequest.MAX_SIZE)).isEqualTo(first);
    final ArgumentCaptor<KeysetPageRequest> requests =
        ArgumentCaptor.forClass(KeysetPageRequest.class);
    verify(workspaces, times(2))
        .findKeysetPageByOrganizationId(eq(organizationId), requests.capture());
    assertThat(requests.getAllValues().get(1).after()).isEqualTo(firstPage.endCursor());
  }

  // TD-PERF-032: a pathological tenant costs a bounded amount of work, never an unbounded loop.
  @Test
  void stopsAtTheCeilingEvenIfMorePagesExist() {
    when(workspaces.findKeysetPageByOrganizationId(eq(organizationId), any()))
        .thenAnswer(invocation -> page(workspaces(KeysetPageRequest.MAX_SIZE), true));

    final List<Workspace> result =
        service.handle(new ListWorkspacesForOrganizationQuery(organizationId));

    assertThat(result).hasSize(ListWorkspacesForOrganizationService.MAX_WORKSPACES);
    verify(
            workspaces,
            times(ListWorkspacesForOrganizationService.MAX_WORKSPACES / KeysetPageRequest.MAX_SIZE))
        .findKeysetPageByOrganizationId(eq(organizationId), any());
  }
}
