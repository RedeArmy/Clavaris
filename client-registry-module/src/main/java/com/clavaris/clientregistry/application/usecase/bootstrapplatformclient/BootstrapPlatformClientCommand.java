package com.clavaris.clientregistry.application.usecase.bootstrapplatformclient;

/**
 * @param rawClientSecret never logged, never persisted as-is — hashed by {@link ClientSecretHasher}
 *     before it touches {@link PlatformClientRepository}, same discipline as {@code
 *     RegisterAccountCommand.rawPassword} (BR-ID-01's principle applied to this credential).
 */
public record BootstrapPlatformClientCommand(String clientId, String rawClientSecret) {

  /**
   * BR-ID-01's principle: never print the raw secret — see RegisterAccountCommand's identical
   * override for the full rationale.
   */
  @Override
  public String toString() {
    return "BootstrapPlatformClientCommand[clientId=" + clientId + ", rawClientSecret=[REDACTED]]";
  }
}
