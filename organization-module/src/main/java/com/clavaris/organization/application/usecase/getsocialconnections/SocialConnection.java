package com.clavaris.organization.application.usecase.getsocialconnections;

import com.clavaris.organization.domain.model.SocialProvider;
import java.util.Optional;

/**
 * One provider as the Connections page shows it. The client secret is never part of this: once
 * saved, it does not leave the system again, not even encrypted.
 *
 * @param clientId the Organization's own client id, empty when it has no credentials
 * @param redirectUri where the provider must be told to send people back, which is the same shared
 *     callback whoever's credentials are in use
 */
public record SocialConnection(
    SocialProvider provider,
    SocialConnectionStatus status,
    boolean enabled,
    boolean hasCredentials,
    Optional<String> clientId,
    boolean blockEmailSubaddresses,
    boolean alwaysShowAccountSelector,
    String redirectUri) {}
