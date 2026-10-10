package com.clavaris.identity.infrastructure.adapter.in.web;

import com.clavaris.identity.application.usecase.resolveclientbranding.ClientBrandingProvider;
import com.clavaris.identity.domain.model.OrganizationId;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseBody;

/**
 * Serves the one CSS rule that gives a consuming application's sign-in and consent pages its brand
 * colour: {@code :root { --clavaris-brand-color: #rrggbb; }}.
 *
 * <p>It is a stylesheet of its own, not an inline {@code <style>}, because the pages' content
 * security policy ({@code style-src 'self'}) forbids inline styles; a same-origin file is allowed.
 * Public, like the logo: a stylesheet link carries no credentials. The colour is checked again
 * here, whatever stored it, so only a plain hex colour can ever reach the response; anything else
 * yields an empty stylesheet and the pages keep the default colour.
 */
@Controller
public class ConsumerThemeController {

  private static final Pattern HEX_COLOUR = Pattern.compile("^#[0-9A-Fa-f]{3}([0-9A-Fa-f]{3})?$");

  // Short, because the colour can change and the URL does not say when it did.
  private static final Duration MAX_AGE = Duration.ofMinutes(5);

  private final ClientBrandingProvider brandingProvider;

  public ConsumerThemeController(final ClientBrandingProvider brandingProvider) {
    this.brandingProvider = brandingProvider;
  }

  /** Where the stylesheet for this Organization (and, if given, client) is served. */
  public static String urlFor(final OrganizationId organizationId, final String clientId) {
    final String path = "/o/" + organizationId.value() + "/branding/theme.css";
    return clientId == null || clientId.isBlank()
        ? path
        : path + "?clientId=" + URLEncoder.encode(clientId, StandardCharsets.UTF_8);
  }

  @GetMapping("/o/{organizationId}/branding/theme.css")
  @ResponseBody
  public ResponseEntity<String> theme(
      @PathVariable final UUID organizationId,
      @RequestParam(name = "clientId", required = false) final String clientId) {
    final String css =
        brandingProvider
            .brandingFor(new OrganizationId(organizationId), clientId)
            .primaryColor()
            .filter(colour -> HEX_COLOUR.matcher(colour).matches())
            .map(colour -> ":root { --clavaris-brand-color: " + colour + "; }\n")
            .orElse("");
    return ResponseEntity.ok()
        .contentType(MediaType.parseMediaType("text/css;charset=UTF-8"))
        .cacheControl(CacheControl.maxAge(MAX_AGE).cachePublic())
        .header("X-Content-Type-Options", "nosniff")
        .body(css);
  }
}
