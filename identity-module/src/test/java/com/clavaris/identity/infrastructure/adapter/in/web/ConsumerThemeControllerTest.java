package com.clavaris.identity.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.clavaris.identity.application.usecase.resolveclientbranding.ClientBrandingProvider;
import com.clavaris.identity.application.usecase.resolveclientbranding.ClientBrandingSnapshot;
import com.clavaris.identity.domain.model.OrganizationId;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

/** The brand colour's stylesheet: a plain hex colour in one rule, or nothing at all. */
class ConsumerThemeControllerTest {

  private static final UUID ORGANIZATION = UUID.randomUUID();

  private final ClientBrandingProvider branding = mock(ClientBrandingProvider.class);
  private final ConsumerThemeController controller = new ConsumerThemeController(branding);

  private void colourIs(final String clientId, final String colour) {
    when(branding.brandingFor(new OrganizationId(ORGANIZATION), clientId))
        .thenReturn(
            new ClientBrandingSnapshot(
                Optional.empty(), Optional.ofNullable(colour), Optional.empty()));
  }

  @Test
  void theColourIsServedAsTheBrandColourVariable() {
    colourIs("acme-web", "#2563eb");

    final ResponseEntity<String> response = controller.theme(ORGANIZATION, "acme-web");

    assertThat(response.getBody()).isEqualTo(":root { --clavaris-brand-color: #2563eb; }\n");
    assertThat(response.getHeaders().getContentType().toString()).startsWith("text/css");
    assertThat(response.getHeaders().getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
    assertThat(response.getHeaders().getCacheControl()).contains("max-age=300").contains("public");
  }

  @Test
  void aThreeDigitColourIsAccepted() {
    colourIs(null, "#abc");

    assertThat(controller.theme(ORGANIZATION, null).getBody()).contains("#abc");
  }

  @Test
  void noColourIsAnEmptyStylesheet() {
    colourIs(null, null);

    assertThat(controller.theme(ORGANIZATION, null).getBody()).isEmpty();
  }

  // Whatever stored it, only a plain hex colour may reach a stylesheet.
  @Test
  void anythingThatIsNotAHexColourIsNeverWritten() {
    for (final String bad :
        new String[] {"red", "#12", "#1234567", "url(x)", "#fff; } body { display:none", "#ggg"}) {
      colourIs(null, bad);

      assertThat(controller.theme(ORGANIZATION, null).getBody()).as(bad).isEmpty();
    }
  }

  @Test
  void theUrlNamesTheOrganizationAndTheEncodedClient() {
    final OrganizationId id = new OrganizationId(ORGANIZATION);

    assertThat(ConsumerThemeController.urlFor(id, null))
        .isEqualTo("/o/" + ORGANIZATION + "/branding/theme.css");
    assertThat(ConsumerThemeController.urlFor(id, " "))
        .isEqualTo("/o/" + ORGANIZATION + "/branding/theme.css");
    assertThat(ConsumerThemeController.urlFor(id, "a b&c"))
        .isEqualTo("/o/" + ORGANIZATION + "/branding/theme.css?clientId=a+b%26c");
  }
}
