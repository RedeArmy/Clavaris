package com.clavaris.organization.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.multipart.MultipartFile;

/**
 * What the dashboard's edit dialog submits for one Organization. The limits mirror {@code
 * OrganizationProfile}'s and {@code Organization}'s own, so a mistake is explained next to the
 * field rather than surfacing as a rejected save.
 *
 * <p>{@code after}/{@code before} are the dashboard's own page cursors, carried only so that saving
 * returns to the page the person was on.
 */
// PMD.DataClass: a form-backing bean is nothing but its fields, same as every sibling *Form here.
@SuppressWarnings("PMD.DataClass")
public class UpdateOrganizationProfileForm {

  @NotBlank(message = "Name is required")
  @Size(max = 255, message = "Name must be at most 255 characters")
  private String name;

  @Size(max = 500, message = "Description must be at most 500 characters")
  private String description;

  @Size(max = 100, message = "Application name must be at most 100 characters")
  private String applicationName;

  @Pattern(regexp = "^$|^#[0-9A-Fa-f]{6}$", message = "Use a hex colour such as #2563eb")
  private String brandColor;

  private MultipartFile logo;
  private boolean removeLogo;
  private String after;
  private String before;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public UpdateOrganizationProfileForm() {
    // Intentionally empty.
  }

  public String getName() {
    return name;
  }

  public void setName(final String name) {
    this.name = name;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(final String description) {
    this.description = description;
  }

  public String getApplicationName() {
    return applicationName;
  }

  public void setApplicationName(final String applicationName) {
    this.applicationName = applicationName;
  }

  public String getBrandColor() {
    return brandColor;
  }

  public void setBrandColor(final String brandColor) {
    this.brandColor = brandColor;
  }

  public MultipartFile getLogo() {
    return logo;
  }

  public void setLogo(final MultipartFile logo) {
    this.logo = logo;
  }

  public boolean isRemoveLogo() {
    return removeLogo;
  }

  public void setRemoveLogo(final boolean removeLogo) {
    this.removeLogo = removeLogo;
  }

  public String getAfter() {
    return after;
  }

  public void setAfter(final String after) {
    this.after = after;
  }

  public String getBefore() {
    return before;
  }

  public void setBefore(final String before) {
    this.before = before;
  }
}
