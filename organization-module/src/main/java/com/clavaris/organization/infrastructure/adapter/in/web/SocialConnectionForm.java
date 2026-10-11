package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.application.usecase.savesocialconnection.SaveSocialConnectionCommand;
import com.clavaris.organization.domain.model.SocialProvider;
import jakarta.validation.constraints.Size;
import java.util.UUID;

/**
 * What the dashboard's connection page submits for one provider. A blank client id or secret keeps
 * the one already saved, so a connection can be edited without retyping a secret that is never
 * shown again. The limits only keep a pasted mistake from reaching the encryption or the database.
 */
// PMD.DataClass: a form-backing bean is nothing but its fields, same as every sibling *Form here.
// PMD.LongVariable: the names say exactly what each setting is.
@SuppressWarnings({"PMD.DataClass", "PMD.LongVariable"})
public class SocialConnectionForm {

  private boolean enabled;

  @Size(max = 255, message = "Client ID must be at most 255 characters")
  private String clientId;

  @Size(max = 1000, message = "Client secret must be at most 1000 characters")
  private String clientSecret;

  private boolean blockEmailSubaddresses;
  private boolean alwaysShowAccountSelector;

  @SuppressWarnings("PMD.UnnecessaryConstructor")
  public SocialConnectionForm() {
    // Intentionally empty.
  }

  public boolean isEnabled() {
    return enabled;
  }

  public void setEnabled(final boolean enabled) {
    this.enabled = enabled;
  }

  public String getClientId() {
    return clientId;
  }

  public void setClientId(final String clientId) {
    this.clientId = clientId;
  }

  public String getClientSecret() {
    return clientSecret;
  }

  public void setClientSecret(final String clientSecret) {
    this.clientSecret = clientSecret;
  }

  public boolean isBlockEmailSubaddresses() {
    return blockEmailSubaddresses;
  }

  public void setBlockEmailSubaddresses(final boolean blockEmailSubaddresses) {
    this.blockEmailSubaddresses = blockEmailSubaddresses;
  }

  public boolean isAlwaysShowAccountSelector() {
    return alwaysShowAccountSelector;
  }

  public void setAlwaysShowAccountSelector(final boolean alwaysShowAccountSelector) {
    this.alwaysShowAccountSelector = alwaysShowAccountSelector;
  }

  /** What this form asks for, as the use case takes it. */
  public SaveSocialConnectionCommand toCommand(
      final UUID organizationId, final SocialProvider provider, final AuditActor actor) {
    return new SaveSocialConnectionCommand(
        organizationId,
        provider,
        enabled,
        clientId,
        clientSecret,
        blockEmailSubaddresses,
        alwaysShowAccountSelector,
        actor);
  }
}
