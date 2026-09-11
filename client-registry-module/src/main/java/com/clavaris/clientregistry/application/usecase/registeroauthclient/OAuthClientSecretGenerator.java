package com.clavaris.clientregistry.application.usecase.registeroauthclient;

/**
 * Outbound port — generates a fresh, random raw secret for an {@code OAuthClient}. Same shape as
 * {@code createorganizationclient.OrganizationClientSecretGenerator}, extracted for the same reason
 * (2026-09-11): this port is now shared between {@link RegisterOAuthClientService} (create) and
 * {@code rotateoauthclientsecret.RotateOAuthClientSecretService} (rotate) — the exact "one small
 * port per credential type, reused by both its own create and rotate use cases" shape {@code
 * OrganizationClientSecretGenerator}'s own Javadoc already establishes; before this it was {@code
 * RegisterOAuthClientService}'s own private, unshared {@code SecureRandom} logic, which the new
 * rotate use case would otherwise have had to duplicate. Implemented by {@code
 * infrastructure.adapter.out.security.SecureRandomOAuthClientSecretGenerator}.
 */
@FunctionalInterface
public interface OAuthClientSecretGenerator {

  String generate();
}
