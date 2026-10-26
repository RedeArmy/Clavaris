package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditEvent;
import com.clavaris.common.domain.model.KeysetCursor;
import com.clavaris.common.domain.model.auditlog.AuditCategory;
import com.clavaris.common.domain.model.auditlog.AuditDetailFormatter;
import com.clavaris.common.domain.model.auditlog.AuditLogEntryView;
import com.clavaris.common.domain.model.auditlog.AuditLogPresenter;
import com.clavaris.organization.application.usecase.getauditlogfororganization.AuditLogPage;
import com.clavaris.organization.application.usecase.getauditlogfororganization.GetAuditLogForOrganizationQuery;
import com.clavaris.organization.application.usecase.getauditlogfororganization.GetAuditLogForOrganizationUseCase;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountQuery;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationQuery;
import com.clavaris.organization.application.usecase.listworkspacerolesfororganization.ListWorkspaceRolesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.listworkspacesfororganization.ListWorkspacesForOrganizationQuery;
import com.clavaris.organization.application.usecase.listworkspacesfororganization.ListWorkspacesForOrganizationUseCase;
import com.clavaris.organization.domain.model.Organization;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * ADR-0025: the dashboard's own audit-log view — {@code GET
 * /platform/dashboard/organizations/{organizationId}/audit-log}. The first fully read-only
 * controller in this codebase's dashboard: no {@code @PostMapping}, no form, no HTMX fragment — a
 * plain list, paged by cursor ("Older events") over the tenant key the audit table carries since
 * TD-ARCH-038. Linked from {@code organization-detail.html} as its own page rather than inlined
 * (unlike the small Rate Limit display) — up to 100 rows doesn't belong crammed into that page the
 * way a single-value display does.
 *
 * <p>The page is written for the person reading it, not for an operator: each raw event is turned
 * into a sentence ("Secret Key deleted") with who did it ("You"), when ("3 hours ago"), and the
 * detail with workspace and role names in place of ids ({@link AuditLogPresenter}). The raw values
 * stay available behind a collapsed "Technical details" disclosure. {@code ?category=} narrows the
 * list to one group of events ({@link AuditCategory}); the group chips show how many events each
 * holds.
 *
 * <p>{@code organizationId} resolves through {@link GetOrganizationForPlatformAccountUseCase} —
 * never a bare repository call — same anti-enumeration posture every other dashboard controller in
 * this codebase already establishes: an organizationId this {@code PlatformAccount} doesn't own
 * 404s identically to one that doesn't exist.
 */
// PMD.ExcessiveImports: the audit-log view's presenter, categories and use case are each a distinct
// collaborator of this one page; splitting the controller would only spread them.
@SuppressWarnings("PMD.ExcessiveImports")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/audit-log")
public class PlatformAuditLogController {

  private static final String AUDIT_LOG_VIEW = "organization/platform/audit-log";

  private final GetOrganizationForPlatformAccountUseCase getOrganization;
  private final GetAuditLogForOrganizationUseCase getAuditLog;
  private final ListWorkspacesForOrganizationUseCase listWorkspaces;
  private final ListWorkspaceRolesForOrganizationUseCase listRoles;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  /** One filter chip: a group of events and how many the log holds for it. */
  public record CategoryChip(String slug, String title, long count) {}

  public PlatformAuditLogController(
      final GetOrganizationForPlatformAccountUseCase getOrganization,
      final GetAuditLogForOrganizationUseCase getAuditLog,
      final ListWorkspacesForOrganizationUseCase listWorkspaces,
      final ListWorkspaceRolesForOrganizationUseCase listRoles,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getOrganization = getOrganization;
    this.getAuditLog = getAuditLog;
    this.listWorkspaces = listWorkspaces;
    this.listRoles = listRoles;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping
  public String showAuditLog(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @RequestParam(required = false) final String category,
      @RequestParam(required = false) final String olderThan,
      final Model model) {
    final UUID ownerPlatformAccountId =
        currentPlatformAccount
            .resolve(request)
            .orElseThrow(
                () ->
                    new IllegalStateException("No authenticated PlatformAccount on this request"));

    final Organization organization =
        getOrganization
            .handle(
                new GetOrganizationForPlatformAccountQuery(organizationId, ownerPlatformAccountId))
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));

    final AuditLogPage page =
        getAuditLog.handle(new GetAuditLogForOrganizationQuery(organizationId, decode(olderThan)));
    final List<AuditEvent> events = page.events();
    final AuditLogPresenter.Context context =
        new AuditLogPresenter.Context(
            ownerPlatformAccountId.toString(), namesFor(organizationId), Instant.now(), "");
    final List<AuditLogEntryView> all =
        events.stream().map(event -> AuditLogPresenter.present(event, context)).toList();
    final Optional<AuditCategory> selected = AuditCategory.fromSlug(category);

    model.addAttribute("organization", organization);
    model.addAttribute(
        "entries",
        selected
            .map(chosen -> all.stream().filter(e -> e.category() == chosen).toList())
            .orElse(all));
    model.addAttribute("categories", chipsFor(all));
    model.addAttribute("selectedCategory", selected.map(AuditCategory::slug).orElse(""));
    model.addAttribute("totalCount", all.size());
    model.addAttribute("nextOlderThan", page.hasOlder() ? page.nextOlderThan().encode() : null);
    model.addAttribute("onOlderPage", olderThan != null && !olderThan.isBlank());
    return AUDIT_LOG_VIEW;
  }

  // A cursor that does not decode is a malformed request, not a server error.
  private static KeysetCursor decode(final String token) {
    if (token == null || token.isBlank()) {
      return null;
    }
    try {
      return KeysetCursor.decode(token);
    } catch (final IllegalArgumentException | DateTimeParseException _) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST);
    }
  }

  // Only the groups that actually hold events get a chip, in the catalog's own order.
  private static List<CategoryChip> chipsFor(final List<AuditLogEntryView> entries) {
    final Map<AuditCategory, Long> counts = new EnumMap<>(AuditCategory.class);
    for (final AuditLogEntryView entry : entries) {
      counts.merge(entry.category(), 1L, Long::sum);
    }
    return Arrays.stream(AuditCategory.values())
        .filter(counts::containsKey)
        .map(group -> new CategoryChip(group.slug(), group.title(), counts.get(group)))
        .toList();
  }

  // Workspaces and roles that still exist, by id, so "Role changed" can name the roles.
  private AuditDetailFormatter.Names namesFor(final UUID organizationId) {
    return AuditLogNames.existing(
        listWorkspaces.handle(new ListWorkspacesForOrganizationQuery(organizationId)),
        listRoles.handle(new ListWorkspaceRolesForOrganizationQuery(organizationId)));
  }
}
