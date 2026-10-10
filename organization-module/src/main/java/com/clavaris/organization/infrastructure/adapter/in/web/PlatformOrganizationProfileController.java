package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.common.domain.model.KeysetPageRequest;
import com.clavaris.organization.application.usecase.updateorganizationprofile.OrganizationNotFoundException;
import com.clavaris.organization.application.usecase.updateorganizationprofile.UpdateOrganizationProfileCommand;
import com.clavaris.organization.application.usecase.updateorganizationprofile.UpdateOrganizationProfileUseCase;
import com.clavaris.organization.domain.model.OrganizationLogo;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.io.IOException;
import java.time.DateTimeException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.util.StringUtils;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Saves the edit dialog on an Organization's card in "Your organizations": its name, description,
 * the consuming application's name, its brand colour and its logo.
 *
 * <p>A plain multipart form post, like the page's create dialog. A saved edit redirects back to the
 * page the person was on; a rejected one re-renders the whole page with that Organization's dialog
 * open and the submitted values and messages in it, so nothing they typed is lost.
 *
 * <p>An Organization that is not the signed-in account's is a 404, the same as one that does not
 * exist: the dialog cannot be used to probe ids.
 */
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/dashboard/organizations/{organizationId}/profile")
public class PlatformOrganizationProfileController {

  private static final String DASHBOARD_VIEW = "organization/platform/dashboard";
  private static final String DASHBOARD_PATH = "/platform/dashboard";
  private static final String LOGO_FIELD = "logo";
  private static final String NAME_FIELD = "name";

  private final UpdateOrganizationProfileUseCase updateProfile;
  private final OrganizationDashboardModel dashboardModel;
  private final CurrentPlatformAccountResolver currentPlatformAccount;

  public PlatformOrganizationProfileController(
      final UpdateOrganizationProfileUseCase updateProfile,
      final OrganizationDashboardModel dashboardModel,
      final CurrentPlatformAccountResolver currentPlatformAccount) {
    this.updateProfile = updateProfile;
    this.dashboardModel = dashboardModel;
    this.currentPlatformAccount = currentPlatformAccount;
  }

  // Three exits (rejected, saved, not the caller's): each is a different response, not a branch of
  // one — same rationale as the sibling dashboard controllers' own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  public String update(
      @PathVariable final UUID organizationId,
      @Valid @ModelAttribute("editForm") final UpdateOrganizationProfileForm form,
      final BindingResult bindingResult,
      final HttpServletRequest request,
      final Model model) {
    final UUID owner =
        currentPlatformAccount
            .resolve(request)
            .orElseThrow(
                () ->
                    new IllegalStateException("No authenticated PlatformAccount on this request"));

    final OrganizationLogo logo = readLogo(organizationId, form, bindingResult);
    if (!bindingResult.hasErrors()) {
      try {
        updateProfile.handle(
            new UpdateOrganizationProfileCommand(
                organizationId,
                owner,
                form.getName(),
                form.getDescription(),
                form.getApplicationName(),
                form.getBrandColor(),
                logo,
                form.isRemoveLogo(),
                AuditActor.platformAccount(owner)));
        return "redirect:" + dashboardPath(form);
      } catch (final OrganizationNotFoundException e) {
        throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Organization not found", e);
      } catch (final IllegalArgumentException e) {
        // Anything the form's own limits did not catch: the domain's answer, shown on the name.
        bindingResult.rejectValue(NAME_FIELD, "invalid", e.getMessage());
      }
    }
    return reopenDialog(model, owner, organizationId, form, bindingResult);
  }

  // The uploaded image, checked as the domain checks it (type, size, and that its bytes really are
  // that type). A problem is a message on the logo field; no upload is simply "no change".
  // PMD.LawOfDemeter: the form hands over its upload and this is the one place that reads it.
  @SuppressWarnings("PMD.LawOfDemeter")
  private static OrganizationLogo readLogo(
      final UUID organizationId,
      final UpdateOrganizationProfileForm form,
      final BindingResult errors) {
    final MultipartFile file = form.getLogo();
    OrganizationLogo logo = null;
    if (file != null && !file.isEmpty()) {
      try {
        logo = OrganizationLogo.of(organizationId, file.getContentType(), file.getBytes());
      } catch (final IllegalArgumentException e) {
        errors.rejectValue(LOGO_FIELD, "invalid", e.getMessage());
      } catch (final IOException e) {
        errors.rejectValue(LOGO_FIELD, "unreadable", "The file could not be read");
      }
    }
    return logo;
  }

  // The dialog shows one message under each field: the first one recorded for it.
  private static Map<String, String> firstMessagePerField(final BindingResult errors) {
    final Map<String, String> messages = new LinkedHashMap<>();
    errors
        .getFieldErrors()
        .forEach(error -> messages.putIfAbsent(error.getField(), error.getDefaultMessage()));
    return messages;
  }

  private String reopenDialog(
      final Model model,
      final UUID owner,
      final UUID organizationId,
      final UpdateOrganizationProfileForm form,
      final BindingResult errors) {
    dashboardModel.populate(model, owner, pageRequest(form));
    model.addAttribute("form", new CreateOrganizationForm());
    model.addAttribute("editingOrganizationId", organizationId);
    model.addAttribute("editErrors", firstMessagePerField(errors));
    model.addAttribute("pageAfter", form.getAfter());
    model.addAttribute("pageBefore", form.getBefore());
    return DASHBOARD_VIEW;
  }

  // The cursors come from this page's own hidden fields; a tampered one just means "first page".
  // PMD.OnlyOneReturn: the decoded page, or the first one; the two exits are the whole method.
  @SuppressWarnings("PMD.OnlyOneReturn")
  private static KeysetPageRequest pageRequest(final UpdateOrganizationProfileForm form) {
    try {
      return KeysetPageRequest.fromCursors(
          blankToNull(form.getAfter()), blankToNull(form.getBefore()));
    } catch (final IllegalArgumentException | DateTimeException e) {
      // A malformed token: not a real flow, so fall back to the first page rather than failing a
      // save.
      return KeysetPageRequest.first();
    }
  }

  private static String dashboardPath(final UpdateOrganizationProfileForm form) {
    final UriComponentsBuilder path = UriComponentsBuilder.fromPath(DASHBOARD_PATH);
    if (StringUtils.hasText(form.getAfter())) {
      path.queryParam("after", form.getAfter());
    } else if (StringUtils.hasText(form.getBefore())) {
      path.queryParam("before", form.getBefore());
    }
    return path.build().encode().toUriString();
  }

  private static String blankToNull(final String value) {
    return StringUtils.hasText(value) ? value : null;
  }
}
