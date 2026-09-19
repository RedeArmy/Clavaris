package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AddAccessRestrictionEntryCommand;
import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AddAccessRestrictionEntryUseCase;
import com.clavaris.organization.application.usecase.addaccessrestrictionentry.DuplicateAccessRestrictionEntryException;
import com.clavaris.organization.application.usecase.listaccessrestrictionentriesfororganization.ListAccessRestrictionEntriesForOrganizationQuery;
import com.clavaris.organization.application.usecase.listaccessrestrictionentriesfororganization.ListAccessRestrictionEntriesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.removeaccessrestrictionentry.AccessRestrictionEntryNotFoundException;
import com.clavaris.organization.application.usecase.removeaccessrestrictionentry.RemoveAccessRestrictionEntryCommand;
import com.clavaris.organization.application.usecase.removeaccessrestrictionentry.RemoveAccessRestrictionEntryUseCase;
import com.clavaris.organization.domain.model.AccessRestrictionEntry;
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

  /* package */ AccessRestrictionEntriesController(
      final AddAccessRestrictionEntryUseCase addEntry,
      final RemoveAccessRestrictionEntryUseCase removeEntry,
      final ListAccessRestrictionEntriesForOrganizationUseCase listEntries) {
    this.addEntry = addEntry;
    this.removeEntry = removeEntry;
    this.listEntries = listEntries;
  }

  @GetMapping
  /* package */ List<AccessRestrictionEntryResponse> list(@PathVariable final UUID organizationId) {
    return listEntries
        .handle(new ListAccessRestrictionEntriesForOrganizationQuery(organizationId))
        .stream()
        .map(AccessRestrictionEntryResponse::from)
        .toList();
  }

  // Two exits (409 duplicate, 201 success) — same rationale as every other admin-mutation
  // controller in this codebase.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  /* package */ ResponseEntity<AccessRestrictionEntryResponse> add(
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
      return ResponseEntity.status(HttpStatus.CONFLICT).build();
    }
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
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
