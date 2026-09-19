package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Web-layer form object for the dashboard "Users" tab's "Create user" modal (Clerk parity) — HTML
 * form field names, Bean Validation annotations, deliberately not the same type as {@link
 * com.clavaris.identity.application.usecase.admincreateaccountfororganization.AdminCreateAccountForOrganizationCommand},
 * same split {@code RegisterAccountForm}'s own Javadoc documents. First/last name/username/phone
 * are all optional — every one of this class's own fields except email and password may be blank.
 */
// PMD.LongVariable: ignorePasswordPolicy/ignoreAccessRestrictions name exactly what each checkbox
// on the create-user form does — same rationale AdminCreateAccountForOrganizationCommand's own
// identical suppression documents.
@SuppressWarnings({"PMD.DataClass", "PMD.LongVariable"})
public class AdminCreateAccountForm {

  private String firstName;
  private String lastName;

  // Max mirrors domain.model.Email.MAX_LENGTH (254, RFC 5321 §4.5.3.1.3) — same fast client/server
  // round trip reason RegisterAccountForm's own sibling field documents.
  @NotBlank(message = "Email is required")
  @Email(message = "Enter a valid email address")
  @Size(max = 254, message = "Email must be at most 254 characters")
  private String email;

  private String username;

  // @Size stays a fast client-side round trip only — the authoritative PasswordPolicy check
  // (bypassable via ignorePasswordPolicy) runs again inside the use case regardless.
  @NotBlank(message = "Password is required")
  private String password;

  private String phoneNumber;
  private boolean ignorePasswordPolicy;
  private boolean ignoreAccessRestrictions;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public AdminCreateAccountForm() {
    // Intentionally empty — Spring MVC/Thymeleaf just need a no-arg constructor to bind
    // form-submitted values onto via the setters below; there's no state to initialise here.
  }

  public String getFirstName() {
    return firstName;
  }

  public void setFirstName(final String firstName) {
    this.firstName = firstName;
  }

  public String getLastName() {
    return lastName;
  }

  public void setLastName(final String lastName) {
    this.lastName = lastName;
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(final String email) {
    this.email = email;
  }

  public String getUsername() {
    return username;
  }

  public void setUsername(final String username) {
    this.username = username;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(final String password) {
    this.password = password;
  }

  public String getPhoneNumber() {
    return phoneNumber;
  }

  public void setPhoneNumber(final String phoneNumber) {
    this.phoneNumber = phoneNumber;
  }

  public boolean isIgnorePasswordPolicy() {
    return ignorePasswordPolicy;
  }

  public void setIgnorePasswordPolicy(final boolean ignorePasswordPolicy) {
    this.ignorePasswordPolicy = ignorePasswordPolicy;
  }

  public boolean isIgnoreAccessRestrictions() {
    return ignoreAccessRestrictions;
  }

  public void setIgnoreAccessRestrictions(final boolean ignoreAccessRestrictions) {
    this.ignoreAccessRestrictions = ignoreAccessRestrictions;
  }

  /** BR-ID-01: never print the password — same rationale as every sibling form's own override. */
  @Override
  public String toString() {
    return "AdminCreateAccountForm[email="
        + email
        + ", username="
        + username
        + ", password=[REDACTED]]";
  }
}
