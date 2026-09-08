package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.confirmnewplatformdeviceloginalert.ConfirmNewPlatformDeviceLoginAlertCommand;
import com.clavaris.identity.application.usecase.confirmnewplatformdeviceloginalert.ConfirmNewPlatformDeviceLoginAlertUseCase;
import com.clavaris.identity.application.usecase.confirmnewplatformdeviceloginalert.InvalidNewPlatformDeviceLoginAlertException;
import jakarta.validation.Valid;
import org.springframework.stereotype.Controller;
import org.springframework.ui.Model;
import org.springframework.validation.BindingResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.ModelAttribute;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;

/**
 * TD-FUT-031: platform-tier mirror of {@link ConfirmNewDeviceLoginAlertController}, no {@code
 * organizationId} in the path — same {@code /platform/...} flat shape as {@link
 * ResetPlatformAccountPasswordController}. Reuses {@link ConfirmNewDeviceLoginAlertForm} as-is (no
 * tenant-specific field on it, same precedent {@link ResetPlatformAccountPasswordController}'s own
 * Javadoc documents for {@link ConfirmPasswordResetForm}).
 *
 * <p>{@code CONFIRM_VIEW} also reuses the tenant-tier's own {@code
 * identity/account-alert-lock-confirm} template directly, not a second {@code
 * identity/platform/...} copy — SonarCloud duplication finding (2026-09-09): the two were
 * byte-identical except for one comment, since neither the form nor the CSRF handling differs by
 * tier. {@code identity/platform/account-alert-lock-success} stays a separate file, unaffected by
 * this finding.
 */
@Controller
@RequestMapping("/platform/account-alert/lock")
public class ConfirmNewPlatformDeviceLoginAlertController {

  private static final String CONFIRM_VIEW = "identity/account-alert-lock-confirm";
  private static final String INVALID_VIEW = "identity/platform/verification-link-invalid";

  private final ConfirmNewPlatformDeviceLoginAlertUseCase useCase;

  public ConfirmNewPlatformDeviceLoginAlertController(
      final ConfirmNewPlatformDeviceLoginAlertUseCase useCase) {
    this.useCase = useCase;
  }

  @GetMapping
  public String showConfirmForm(@RequestParam final String token, final Model model) {
    final ConfirmNewDeviceLoginAlertForm form = new ConfirmNewDeviceLoginAlertForm();
    form.setToken(token);
    model.addAttribute("form", form);
    return CONFIRM_VIEW;
  }

  // Same "several independent rejection reasons, each needs its own exit" rationale as
  // ConfirmNewDeviceLoginAlertController's own identical suppression.
  @SuppressWarnings("PMD.OnlyOneReturn")
  @PostMapping
  public String confirm(
      @Valid @ModelAttribute("form") final ConfirmNewDeviceLoginAlertForm form,
      final BindingResult bindingResult) {
    if (bindingResult.hasErrors()) {
      return INVALID_VIEW;
    }

    try {
      useCase.handle(new ConfirmNewPlatformDeviceLoginAlertCommand(form.getToken()));
    } catch (final InvalidNewPlatformDeviceLoginAlertException _) {
      return INVALID_VIEW;
    }

    return "redirect:/platform/account-alert/lock/success";
  }

  @GetMapping("/success")
  public String success() {
    return "identity/platform/account-alert-lock-success";
  }
}
