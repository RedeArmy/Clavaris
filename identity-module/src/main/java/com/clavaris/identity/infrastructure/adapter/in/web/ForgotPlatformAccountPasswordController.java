package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.requestplatformaccountpasswordreset.RequestPlatformAccountPasswordResetCommand;
import com.clavaris.identity.application.usecase.requestplatformaccountpasswordreset.RequestPlatformAccountPasswordResetUseCase;
import com.clavaris.identity.domain.model.Email;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;

/**
 * Mirrors {@link ForgotPasswordController}, no {@code organizationId} in the path.
 *
 * <p><b>Live UX bug, 2026-09-22:</b> unlike its tenant-tier sibling, this flow is also reachable
 * from inside {@code PlatformAccountProfileController}'s own "Manage account" dialog (Security tab,
 * "Change password") — a plain link there used to always do a full browser navigation away from the
 * dialog, even when the dialog was the one thing that opened it. Every GET/POST handler below now
 * checks {@link #isHtmxRequest(HttpServletRequest)} the same dual-mode way {@code
 * PlatformAccountProfileController} already does: an htmx-originated request (the dialog's own
 * "Change password" link, and this page's own form once loaded that way) re-renders {@code ::
 * content} in place, swapped into {@code #manage-account-dialog-body}; a real, direct request (this
 * page's own standalone URL, reached from {@code /platform/login}'s "Forgot your password?") keeps
 * the exact old full-page behavior, unchanged. The success path still issues a real {@code
 * redirect:} either way — htmx follows a same-origin redirect transparently, preserving the
 * original request's own {@code HX-Request} header across it (confirmed against the Fetch spec, not
 * assumed), so {@link #pending}'s own dual-mode check still sees it correctly without this
 * controller needing a separate non-redirect success branch.
 */
// PMD.LongVariable: INSIDE_DIALOG_ATTRIBUTE names exactly what it is — same convention every other
// descriptively-named constant in this codebase already establishes (e.g.
// PlatformAccountProfileController's
// own class-level suppression for the same rule).
@SuppressWarnings("PMD.LongVariable")
@Controller
@RequestMapping("/platform/forgot-password")
public class ForgotPlatformAccountPasswordController {

  private static final String HX_REQUEST_HEADER = "HX-Request";
  private static final String FORM_VIEW = "identity/platform/forgot-password";
  private static final String FORM_FRAGMENT = FORM_VIEW + " :: content";
  private static final String PENDING_VIEW = "identity/platform/forgot-password-pending";
  private static final String PENDING_FRAGMENT = PENDING_VIEW + " :: content";
  private static final String INSIDE_DIALOG_ATTRIBUTE = "insideDialog";

  private final RequestPlatformAccountPasswordResetUseCase useCase;

  public ForgotPlatformAccountPasswordController(
      final RequestPlatformAccountPasswordResetUseCase useCase) {
    this.useCase = useCase;
  }

  @GetMapping
  public String showForm(final HttpServletRequest request, final Model model) {
    model.addAttribute("form", new RequestPasswordResetForm());
    model.addAttribute(INSIDE_DIALOG_ATTRIBUTE, isHtmxRequest(request));
    return isHtmxRequest(request) ? FORM_FRAGMENT : FORM_VIEW;
  }

  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String requestReset(
      @Valid @ModelAttribute("form") final RequestPasswordResetForm form,
      final BindingResult bindingResult,
      final HttpServletRequest request,
      final Model model) {
    if (bindingResult.hasErrors()) {
      model.addAttribute(INSIDE_DIALOG_ATTRIBUTE, isHtmxRequest(request));
      return isHtmxRequest(request) ? FORM_FRAGMENT : FORM_VIEW;
    }

    useCase.handle(new RequestPlatformAccountPasswordResetCommand(new Email(form.getEmail())));

    return "redirect:/platform/forgot-password/pending";
  }

  @GetMapping("/pending")
  public String pending(final HttpServletRequest request, final Model model) {
    model.addAttribute(INSIDE_DIALOG_ATTRIBUTE, isHtmxRequest(request));
    return isHtmxRequest(request) ? PENDING_FRAGMENT : PENDING_VIEW;
  }

  private static boolean isHtmxRequest(final HttpServletRequest request) {
    return "true".equals(request.getHeader(HX_REQUEST_HEADER));
  }
}
