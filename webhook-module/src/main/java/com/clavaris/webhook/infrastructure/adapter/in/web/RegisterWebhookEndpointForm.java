package com.clavaris.webhook.infrastructure.adapter.in.web;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.ArrayList;
import java.util.List;

/**
 * Web-layer form object for the dashboard's own "register webhook endpoint" form — separate from
 * {@link RegisterWebhookEndpointRequest}, the REST API's own DTO (same "web knows about forms, not
 * the domain" split {@code CreateOrganizationClientForm}'s own Javadoc documents). {@code
 * subscribedEventTypes} is rendered as one checkbox per {@link KnownWebhookEventTypeOptions}
 * constant — see that class's own Javadoc for why that's a UI convenience, not a domain-enforced
 * vocabulary.
 *
 * <p>PMD.DataClass: the deliberate record-style form-object convention this codebase's own {@code
 * CreateOrganizationClientForm} etc. already establish. PMD.LongVariable: {@code
 * subscribedEventTypes} matches the domain's own field name exactly, not arbitrarily long — same
 * precedent {@code WebhookEndpoint}'s own suppression already establishes.
 */
@SuppressWarnings({"PMD.DataClass", "PMD.LongVariable"})
public class RegisterWebhookEndpointForm {

  @NotBlank(message = "Enter the URL to deliver events to")
  private String url = "";

  private String description = "";

  @NotEmpty(message = "Select at least one event type")
  private List<String> subscribedEventTypes = new ArrayList<>();

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public RegisterWebhookEndpointForm() {
    // Intentionally empty.
  }

  public String getUrl() {
    return url;
  }

  public void setUrl(final String url) {
    this.url = url == null ? "" : url;
  }

  public String getDescription() {
    return description;
  }

  public void setDescription(final String description) {
    this.description = description == null ? "" : description;
  }

  public List<String> getSubscribedEventTypes() {
    return subscribedEventTypes;
  }

  public void setSubscribedEventTypes(final List<String> subscribedEventTypes) {
    this.subscribedEventTypes =
        subscribedEventTypes == null ? new ArrayList<>() : subscribedEventTypes;
  }
}
