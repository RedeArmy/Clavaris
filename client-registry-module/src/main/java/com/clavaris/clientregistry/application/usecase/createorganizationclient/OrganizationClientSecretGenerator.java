package com.clavaris.clientregistry.application.usecase.createorganizationclient;

/**
 * Outbound port — generates a fresh, random raw secret for an {@code OrganizationClient}. Same
 * shape as {@code rotateplatformclientsecret.PlatformClientSecretGenerator}, deliberately a
 * separate small port rather than a shared one across both credential types — same "two small
 * parallel ports bridged independently" precedent {@code SocialProvider}'s own duplication across
 * modules already established this session, cheaper than an artificial shared abstraction for a
 * one-method interface. Implemented by {@code
 * infrastructure.adapter.out.security.SecureRandomOrganizationClientSecretGenerator}.
 */
@FunctionalInterface
public interface OrganizationClientSecretGenerator {

  String generate();
}
