package com.clavaris.clientregistry.application.usecase.registeroauthclient;

import com.clavaris.clientregistry.domain.model.OAuthClient;

/**
 * @param rawClientSecret shown exactly once, at creation (data-model.md §2's {@code
 *     webhook_endpoints.secret_hash} convention applied here too) — the caller (controller) is
 *     responsible for returning it in the HTTP response and never persisting or logging it itself;
 *     this record's own {@code toString()} is overridden so an accidental log statement elsewhere
 *     can't leak it regardless.
 */
public record RegisterOAuthClientResult(OAuthClient client, String rawClientSecret) {

  @Override
  public String toString() {
    return "RegisterOAuthClientResult[client=" + client + ", rawClientSecret=[REDACTED]]";
  }
}
