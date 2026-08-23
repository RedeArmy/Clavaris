package com.clavaris.clientregistry.application.usecase.rotateplatformclientsecret;

/**
 * @param rawSecret shown here exactly once, never retrievable again — same convention as {@code
 *     RegisterOAuthClientResponse}'s own clientSecret.
 */
public record RotatePlatformClientSecretResult(String clientId, String rawSecret) {}
