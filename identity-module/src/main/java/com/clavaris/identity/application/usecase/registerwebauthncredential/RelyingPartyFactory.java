package com.clavaris.identity.application.usecase.registerwebauthncredential;

import com.yubico.webauthn.RelyingParty;

/**
 * Builds the WebAuthn relying party under a given display name.
 *
 * <p>The name is what the browser or the operating system shows when it asks someone to save a
 * passkey ("Save a passkey for <name>"). For an Account that belongs to a consuming application it
 * has to be that application's Organization, never Clavaris, so the name is chosen per registration
 * instead of being fixed once for the whole deployment. The relying party's id and allowed origins
 * stay the deployment's own: they are what a passkey is bound to and what a browser enforces, and
 * they do not change with the name.
 */
@FunctionalInterface
public interface RelyingPartyFactory {

  /**
   * @param displayName the name to show, or {@code null} or blank to show the relying party's own
   *     id (the host name), which says nothing about Clavaris
   */
  RelyingParty named(String displayName);
}
