package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AccessRestrictionLimitExceededException;
import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AddAccessRestrictionEntryCommand;
import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AddAccessRestrictionEntryService;
import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AddAccessRestrictionEntryUseCase;
import com.clavaris.organization.application.usecase.addaccessrestrictionentry.DuplicateAccessRestrictionEntryException;
import com.clavaris.organization.application.usecase.listaccessrestrictionentriesfororganization.ListAccessRestrictionEntriesForOrganizationQuery;
import com.clavaris.organization.application.usecase.listaccessrestrictionentriesfororganization.ListAccessRestrictionEntriesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.previewaccessrestrictionfororganization.PreviewAccessRestrictionForOrganizationQuery;
import com.clavaris.organization.application.usecase.previewaccessrestrictionfororganization.PreviewAccessRestrictionForOrganizationUseCase;
import com.clavaris.organization.application.usecase.removeaccessrestrictionentry.AccessRestrictionEntryNotFoundException;
import com.clavaris.organization.application.usecase.removeaccessrestrictionentry.RemoveAccessRestrictionEntryCommand;
import com.clavaris.organization.application.usecase.removeaccessrestrictionentry.RemoveAccessRestrictionEntryUseCase;
import com.clavaris.organization.domain.model.AccessRestrictionEntry;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * SDE-III review, 2026-09-19 — Clerk "Restrictions" parity, minimal: {@code
 * /api/v1/admin/organizations/{organizationId}/access-restrictions}, list + add + remove. No
 * dashboard UI section yet for managing these entries (named gap, not silent — the dashboard
 * "Users" create-user modal's "Ignore access restrictions" checkbox is the only UI surface this
 * pass adds); an operator populates the list via this REST API for now.
 */
@RestController
@RequestMapping("/api/v1/admin/organizations/{organizationId}/access-restrictions")
class AccessRestrictionEntriesController {

  private final AddAccessRestrictionEntryUseCase addEntry;
  private final RemoveAccessRestrictionEntryUseCase removeEntry;
  private final ListAccessRestrictionEntriesForOrganizationUseCase listEntries;
  private final PreviewAccessRestrictionForOrganizationUseCase previewAccess;

  /* package */ AccessRestrictionEntriesController(
      final AddAccessRestrictionEntryUseCase addEntry,
      final RemoveAccessRestrictionEntryUseCase removeEntry,
      final ListAccessRestrictionEntriesForOrganizationUseCase listEntries,
      final PreviewAccessRestrictionForOrganizationUseCase previewAccess) {
    this.addEntry = addEntry;
    this.removeEntry = removeEntry;
    this.listEntries = listEntries;
    this.previewAccess = previewAccess;
  }

  @Operation(
      summary = "List an Organization's own access-restriction entries (blocklist/allowlist)")
  @ApiResponse(responseCode = "200", description = "Possibly empty list")
  @GetMapping
  /* package */ List<AccessRestrictionEntryResponse> list(@PathVariable final UUID organizationId) {
    return listEntries
        .handle(new ListAccessRestrictionEntriesForOrganizationQuery(organizationId))
        .stream()
        .map(AccessRestrictionEntryResponse::from)
        .toList();
  }

  // Three exits (201 created, 409 already there, 409 list full) — same rationale as every other
  // admin-mutation controller in this codebase. Both 409s carry a stable error code (TD-SEC-094).
  @Operation(
      summary = "Add an access-restriction entry (blocklist or allowlist) to an Organization")
  @ApiResponse(responseCode = "201", description = "Entry created")
  @ApiResponse(
      responseCode = "409",
      description =
          "Refused. The body's `error` is `entry_already_exists` when an entry with this"
              + " identifier is already there (safe to skip), or `entry_limit_reached` when the"
              + " Organization is at its cap of entries (stop)",
      content =
          @Content(schema = @Schema(implementation = AccessRestrictionConflictResponse.class)))
  @PostMapping
  /* package */ ResponseEntity<?> add(
      @PathVariable final UUID organizationId,
      @Valid @RequestBody final AccessRestrictionEntryRequest request) {
    try {
      final AccessRestrictionEntry entry =
          addEntry.handle(
              new AddAccessRestrictionEntryCommand(
                  organizationId, request.type(), request.identifier()));
      return ResponseEntity.status(HttpStatus.CREATED)
          .body(AccessRestrictionEntryResponse.from(entry));
    } catch (final DuplicateAccessRestrictionEntryException _) {
      // Both are "valid request, but the Organization's current state won't take it" (TD-SEC-087).
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(AccessRestrictionConflictResponse.alreadyExists(request.identifier()));
    } catch (final AccessRestrictionLimitExceededException _) {
      return ResponseEntity.status(HttpStatus.CONFLICT)
          .body(
              AccessRestrictionConflictResponse.limitReached(
                  AddAccessRestrictionEntryService.MAX_ENTRIES_PER_ORGANIZATION));
    }
  }

  // A dry run, not a change: it reads the lists and writes nothing (TD-FUT-069).
  @Operation(
      summary = "Check whether an email would be let in, and which rule decides it",
      description =
          "Evaluates the Organization's blocklist and allowlist for one address without creating"
              + " anything. The reason is one of NOT_RESTRICTED, ALLOWED_BY_ALLOWLIST,"
              + " NOT_ON_ALLOWLIST, BLOCKED_BY_BLOCKLIST, NOT_ON_BLOCKLIST.")
  @ApiResponse(responseCode = "200", description = "The decision and the rule behind it")
  @PostMapping("/check")
  /* package */ AccessRestrictionCheckResponse check(
      @PathVariable final UUID organizationId,
      @Valid @RequestBody final AccessRestrictionCheckRequest request) {
    return AccessRestrictionCheckResponse.from(
        previewAccess.handle(
            new PreviewAccessRestrictionForOrganizationQuery(organizationId, request.email())));
  }

  @Operation(summary = "Remove an access-restriction entry from an Organization")
  @ApiResponse(responseCode = "204", description = "Removed")
  @ApiResponse(responseCode = "404", description = "No entry exists with the given id")
  @DeleteMapping("/{entryId}")
  /* package */ ResponseEntity<Void> remove(
      @PathVariable final UUID organizationId, @PathVariable final UUID entryId) {
    try {
      removeEntry.handle(new RemoveAccessRestrictionEntryCommand(organizationId, entryId));
    } catch (final AccessRestrictionEntryNotFoundException _) {
      return ResponseEntity.notFound().build();
    }
    return ResponseEntity.noContent().build();
  }
}
