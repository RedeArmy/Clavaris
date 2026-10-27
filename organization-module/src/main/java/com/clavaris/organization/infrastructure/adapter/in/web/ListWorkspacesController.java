package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.KeysetPage;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged.ListWorkspacesForOrganizationPagedQuery;
import com.clavaris.organization.application.usecase.listworkspacesfororganizationpaged.ListWorkspacesForOrganizationPagedUseCase;
import com.clavaris.organization.domain.model.Workspace;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * {@code GET /api/v1/admin/organizations/{organizationId}/workspaces} — read-only, no dedicated
 * scope (only mutating admin-API actions get their own scope, same precedent as every other GET
 * endpoint on this surface). An unknown {@code organizationId} simply returns an empty list, same
 * "no distinct not-found state for a listing" convention as this codebase's other list endpoints.
 *
 * <p>TD-PERF-032: keyset-paged. The body stays a bare JSON array, so a caller that never pages
 * still parses it; it now holds at most {@code limit} workspaces (default and ceiling {@value
 * KeysetPageRequest#MAX_SIZE}), newest first, and when more exist the response carries an RFC 8288
 * {@code Link: <...?after=CURSOR&limit=N>; rel="next"} header to follow. A caller with more than
 * {@value KeysetPageRequest#MAX_SIZE} workspaces must follow it; this was the only unbounded list
 * on the admin API that had a keyset sibling waiting.
 */
@RestController
class ListWorkspacesController {

  private final ListWorkspacesForOrganizationPagedUseCase useCase;

  /* package */ ListWorkspacesController(final ListWorkspacesForOrganizationPagedUseCase useCase) {
    this.useCase = useCase;
  }

  @Operation(summary = "List Workspaces within this Organization, one page at a time")
  @ApiResponse(
      responseCode = "200",
      description = "Possibly empty array; a Link header with rel=next when more remain")
  @ApiResponse(responseCode = "400", description = "Malformed cursor or limit out of range")
  @GetMapping("/api/v1/admin/organizations/{organizationId}/workspaces")
  /* package */ ResponseEntity<List<WorkspaceResponse>> list(
      @PathVariable final UUID organizationId,
      @Parameter(description = "Opaque cursor from a previous page's Link header")
          @RequestParam(required = false)
          final String after,
      @RequestParam(defaultValue = "" + KeysetPageRequest.MAX_SIZE) final int limit) {
    final KeysetPage<Workspace> page =
        useCase.handle(
            new ListWorkspacesForOrganizationPagedQuery(organizationId, requestOf(after, limit)));

    final ResponseEntity.BodyBuilder response = ResponseEntity.ok();
    if (page.hasNext()) {
      response.header(
          HttpHeaders.LINK,
          "<"
              + "/api/v1/admin/organizations/"
              + organizationId
              + "/workspaces?after="
              + page.endCursor().encode()
              + "&limit="
              + limit
              + ">; rel=\"next\"");
    }
    return response.body(page.content().stream().map(WorkspaceResponse::from).toList());
  }

  // A malformed cursor or an out-of-range limit is the caller's mistake, not a server error.
  private static KeysetPageRequest requestOf(final String after, final int limit) {
    try {
      return KeysetPageRequest.fromCursors(after, null, limit);
    } catch (final IllegalArgumentException | java.time.format.DateTimeParseException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid_page_request", e);
    }
  }
}
