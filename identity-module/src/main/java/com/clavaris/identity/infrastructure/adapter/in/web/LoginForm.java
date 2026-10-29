package com.clavaris.identity.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Web-layer form object for {@link LoginController} — same shape rationale as {@link
 * RegisterAccountForm}: a mutable bean for Thymeleaf's two-way {@code th:field} binding, not the
 * same type as {@code AuthenticateWithIdentifierCommand}.
 *
 * <p>ADR-0032: the first field holds an email address <em>or</em> a username. It is still named
 * {@code email}: that is the request parameter the login rate limit keys on ({@code
 * RateLimitIdentifiers#emailFormField}) and the one every client of this form already posts, and a
 * second spelling of it would have to be honoured by both the limit and the controller in exactly
 * the same way or one value could be counted while another is checked. Its shape is judged by the
 * use case, not here: a malformed value is the same generic rejection as a wrong password, so there
 * is no {@code @Email} on it.
 */
public class LoginForm {

  @NotBlank(message = "Username or email is required")
  @Size(max = 254, message = "Username or email must be at most 254 characters")
  private String email;

  // No @Size lower/upper bound here: this is a login attempt, not a registration.
  @NotBlank(message = "Password is required")
  private String password;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public LoginForm() {
    // Intentionally empty — Spring MVC/Thymeleaf just need a no-arg constructor to bind
    // form-submitted values onto via the setters below; there's no state to initialise here.
  }

  public String getEmail() {
    return email;
  }

  public void setEmail(final String email) {
    this.email = email;
  }

  public String getPassword() {
    return password;
  }

  public void setPassword(final String password) {
    this.password = password;
  }

  /** BR-ID-01 rationale — same as {@code RegisterAccountForm#toString()}. */
  @Override
  public String toString() {
    return "LoginForm[email=" + email + ", password=[REDACTED]]";
  }
}
