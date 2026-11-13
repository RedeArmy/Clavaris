package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AccessRestrictionLimitExceededException;
import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AddAccessRestrictionEntryCommand;
import com.clavaris.organization.application.usecase.addaccessrestrictionentry.AddAccessRestrictionEntryUseCase;
import com.clavaris.organization.application.usecase.addaccessrestrictionentry.DuplicateAccessRestrictionEntryException;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountQuery;
import com.clavaris.organization.application.usecase.getorganizationforplatformaccount.GetOrganizationForPlatformAccountUseCase;
import com.clavaris.organization.application.usecase.listaccessrestrictionentriesfororganization.ListAccessRestrictionEntriesForOrganizationQuery;
import com.clavaris.organization.application.usecase.listaccessrestrictionentriesfororganization.ListAccessRestrictionEntriesForOrganizationUseCase;
import com.clavaris.organization.application.usecase.previewaccessrestrictionfororganization.PreviewAccessRestrictionForOrganizationQuery;
import com.clavaris.organization.application.usecase.previewaccessrestrictionfororganization.PreviewAccessRestrictionForOrganizationUseCase;
import com.clavaris.organization.application.usecase.removeaccessrestrictionentry.AccessRestrictionEntryNotFoundException;
import com.clavaris.organization.application.usecase.removeaccessrestrictionentry.RemoveAccessRestrictionEntryCommand;
import com.clavaris.organization.application.usecase.removeaccessrestrictionentry.RemoveAccessRestrictionEntryUseCase;
import com.clavaris.organization.domain.model.AccessRestrictionEntry;
import com.clavaris.organization.domain.model.Organization;
import com.clavaris.organization.domain.model.RestrictionType;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.server.ResponseStatusException;

/**
 * Clerk "Restrictions" parity on the dashboard: the Organization's own blocklist and allowlist of
 * email addresses and domains, which until now could only be edited through the admin API while the
 * Users page already carried an "ignore access restrictions" box that referred to lists nobody
 * could see.
 *
 * <p>Same use cases the API calls. The page checks what the API does not: an identifier must be an
 * email address or {@code @domain}, because the domain type accepts any non-blank text and a typo
 * would then sit in the list matching nothing. The allowlist rule is stated on the page, since it
 * is the surprising one: the moment any allowlist entry exists, only the addresses it matches may
 * register and the blocklist is ignored.
 */
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/access-restrictions")
public class PlatformAccessRestrictionsController {

  private static final Logger LOG =
      LoggerFactory.getLogger(PlatformAccessRestrictionsController.class);
  private static final String VIEW = "organization/platform/organization-access-restrictions";
  private static final String BASE = "/platform/dashboard/organizations/";
  // The column an entry is stored in (varchar 320); nothing longer can be on a list, and the form
  // refuses it instead of reaching the database (TD-SEC-087).
  private static final int MAX_IDENTIFIER_LENGTH = 320;

  // An email address, or a domain with a leading "@". Deliberately plain: it only has to catch the
  // typo that would never match, not validate RFC 5322.
  private static final Pattern EMAIL =
      Pattern.compile("^[^@\\s]+@[a-z0-9]([a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}$");
  private static final Pattern DOMAIN =
      Pattern.compile("^@[a-z0-9]([a-z0-9.-]*[a-z0-9])?\\.[a-z]{2,}$");

  private final GetOrganizationForPlatformAccountUseCase getOrganization;
  private final ListAccessRestrictionEntriesForOrganizationUseCase listEntries;
  private final AddAccessRestrictionEntryUseCase addEntry;
  private final RemoveAccessRestrictionEntryUseCase removeEntry;
  private final PreviewAccessRestrictionForOrganizationUseCase previewAccess;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  public PlatformAccessRestrictionsController(
      final GetOrganizationForPlatformAccountUseCase getOrganization,
      final ListAccessRestrictionEntriesForOrganizationUseCase listEntries,
      final AddAccessRestrictionEntryUseCase addEntry,
      final RemoveAccessRestrictionEntryUseCase removeEntry,
      final PreviewAccessRestrictionForOrganizationUseCase previewAccess,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.getOrganization = getOrganization;
    this.listEntries = listEntries;
    this.addEntry = addEntry;
    this.removeEntry = removeEntry;
    this.previewAccess = previewAccess;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  @GetMapping
  public String show(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      final Model model) {
    final Organization organization = requireOwnedOrganization(request, organizationId);
    populate(model, organization, organizationId, null, null);
    return VIEW;
  }

  @PostMapping
  public String add(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @RequestParam(name = "type") final RestrictionType type,
      @RequestParam(name = "identifier", defaultValue = "") final String identifier,
      final Model model) {
    final Organization organization = requireOwnedOrganization(request, organizationId);
    final String normalized = identifier.trim().toLowerCase(Locale.ROOT);
    if (normalized.length() > MAX_IDENTIFIER_LENGTH
        || !EMAIL.matcher(normalized).matches() && !DOMAIN.matcher(normalized).matches()) {
      populate(model, organization, organizationId, "invalid", identifier);
      return VIEW;
    }
    try {
      addEntry.handle(new AddAccessRestrictionEntryCommand(organizationId, type, normalized));
    } catch (final DuplicateAccessRestrictionEntryException _) {
      populate(model, organization, organizationId, "duplicate", identifier);
      return VIEW;
    } catch (final AccessRestrictionLimitExceededException _) {
      populate(model, organization, organizationId, "limit", identifier);
      return VIEW;
    }
    return "redirect:" + BASE + organizationId + "/access-restrictions";
  }

  // TD-FUT-069: a dry run, a POST so the address an operator types never lands in a URL or a log.
  @PostMapping("/preview")
  public String preview(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @RequestParam(name = "email", defaultValue = "") final String email,
      final Model model) {
    final Organization organization = requireOwnedOrganization(request, organizationId);
    final String typed = email.trim();
    populate(model, organization, organizationId, null, null);
    if (typed.isEmpty() || typed.length() > MAX_IDENTIFIER_LENGTH) {
      model.addAttribute("previewError", true);
    } else {
      model.addAttribute(
          "preview",
          previewAccess.handle(
              new PreviewAccessRestrictionForOrganizationQuery(organizationId, typed)));
    }
    model.addAttribute("previewEmail", typed);
    return VIEW;
  }

  @PostMapping("/{entryId}/remove")
  public String remove(
      final HttpServletRequest request,
      @PathVariable final UUID organizationId,
      @PathVariable final UUID entryId) {
    requireOwnedOrganization(request, organizationId);
    try {
      removeEntry.handle(new RemoveAccessRestrictionEntryCommand(organizationId, entryId));
    } catch (final AccessRestrictionEntryNotFoundException _) {
      // Already gone (a second click, or another operator): the list is what the person wanted.
      LOG.info(
          "event=access_restriction_entry_already_removed organizationId={} entryId={}",
          organizationId,
          entryId);
    }
    return "redirect:" + BASE + organizationId + "/access-restrictions";
  }

  private void populate(
      final Model model,
      final Organization organization,
      final UUID organizationId,
      final String error,
      final String typed) {
    final List<AccessRestrictionEntry> entries =
        listEntries.handle(new ListAccessRestrictionEntriesForOrganizationQuery(organizationId));
    model.addAttribute("organizationId", organizationId);
    model.addAttribute("organizationName", organization.name());
    model.addAttribute(
        "allowlist", entries.stream().filter(e -> e.type() == RestrictionType.ALLOWLIST).toList());
    model.addAttribute(
        "blocklist", entries.stream().filter(e -> e.type() == RestrictionType.BLOCKLIST).toList());
    model.addAttribute("restrictionError", error);
    model.addAttribute("typedIdentifier", typed);
  }

  private Organization requireOwnedOrganization(
      final HttpServletRequest request, final UUID organizationId) {
    final UUID owner =
        currentPlatformAccount
            .resolve(request)
            .orElseThrow(
                () ->
                    new IllegalStateException("No authenticated PlatformAccount on this request"));
    return getOrganization
        .handle(new GetOrganizationForPlatformAccountQuery(organizationId, owner))
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
  }
}
