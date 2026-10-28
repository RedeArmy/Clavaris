package com.clavaris.app.infrastructure.adapter.in.web;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.regex.Pattern;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;

/**
 * RFC 9116: {@code /.well-known/security.txt}, where a security researcher looks first to learn how
 * to report a vulnerability. For a credential store that is a basic courtesy that most deployments
 * forget, and it is what a vendor assessment checks for.
 *
 * <p>Off until the operator provides a contact: {@code clavaris.security-txt.contact} and an {@code
 * expires} date in the future, both required by the RFC. With either missing or stale the endpoint
 * answers 404 rather than publishing a contact that nobody reads or an expired file the RFC says to
 * ignore. Values are checked for shape, never echoed raw, so a configuration value can not inject
 * another field.
 */
// @Controller, not @RestController: this is a public well-known file, not an endpoint of the
// management API that the OpenAPI documentation rule (ADR-0008 §3) covers.
@Controller
class SecurityTxtController {

  // A URI the RFC allows for Contact: mailto:, https: or tel:. No whitespace, so no extra line.
  private static final Pattern CONTACT_PATTERN = Pattern.compile("(mailto:|https://|tel:)\\S+");
  private static final Pattern POLICY_PATTERN = Pattern.compile("https://\\S+");
  // A language tag as RFC 9116 lists them ("en", "es"); the list is split and each tag checked,
  // rather than one repeated-group pattern, so the check cannot recurse on a long input.
  private static final Pattern LANGUAGE_TAG = Pattern.compile("[A-Za-z]{2,3}");

  private final String contactUri;
  private final String expires;
  private final String policyUrl;
  private final String preferredLanguages;
  private final String baseUrl;

  /* package */ SecurityTxtController(
      @Value("${clavaris.security-txt.contact:}") final String contact,
      @Value("${clavaris.security-txt.expires:}") final String expires,
      @Value("${clavaris.security-txt.policy:}") final String policy,
      @Value("${clavaris.security-txt.preferred-languages:en, es}") final String languages,
      @Value("${CLAVARIS_BASE_URL:http://localhost:8080}") final String baseUrl) {
    this.contactUri = contact.strip();
    this.expires = expires.strip();
    this.policyUrl = policy.strip();
    this.preferredLanguages = languages.strip();
    this.baseUrl = baseUrl.strip();
  }

  @GetMapping({"/.well-known/security.txt", "/security.txt"})
  /* package */ ResponseEntity<String> securityTxt() {
    final Instant expiry = parseExpiry();
    if (!CONTACT_PATTERN.matcher(contactUri).matches()
        || expiry == null
        || !expiry.isAfter(Instant.now())) {
      return ResponseEntity.notFound().build();
    }
    final StringBuilder body = new StringBuilder();
    body.append("Contact: ").append(contactUri).append('\n');
    body.append("Expires: ").append(expiry).append('\n');
    if (POLICY_PATTERN.matcher(policyUrl).matches()) {
      body.append("Policy: ").append(policyUrl).append('\n');
    }
    if (validLanguages()) {
      body.append("Preferred-Languages: ").append(preferredLanguages).append('\n');
    }
    body.append("Canonical: ").append(baseUrl).append("/.well-known/security.txt\n");
    return ResponseEntity.ok()
        .contentType(new MediaType("text", "plain", java.nio.charset.StandardCharsets.UTF_8))
        .body(body.toString());
  }

  // Comma-separated language tags, each two or three letters; an empty or odd list is left out.
  private boolean validLanguages() {
    if (preferredLanguages.isEmpty()) {
      return false;
    }
    for (final String tag : preferredLanguages.split(",", -1)) {
      if (!LANGUAGE_TAG.matcher(tag.strip()).matches()) {
        return false;
      }
    }
    return true;
  }

  private Instant parseExpiry() {
    try {
      return Instant.parse(expires);
    } catch (final DateTimeParseException _) {
      return null;
    }
  }
}
