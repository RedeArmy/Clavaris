package com.clavaris.organization.application.usecase.savesocialconnection;

import com.clavaris.common.domain.model.AuditActor;
import com.clavaris.organization.domain.model.SocialProvider;
import java.util.UUID;

/**
 * Everything the dashboard's connection page saves for one provider of one Organization.
 *
 * @param clientId the provider app's client id, or blank to keep the one already saved
 * @param rawClientSecret the cleartext secret as typed, or blank to keep the one already saved. It
 *     is encrypted by the service before it reaches the repository, and never persisted or logged
 *     as given.
 */
// PMD.LongVariable: the names say exactly what each setting is.
@SuppressWarnings("PMD.LongVariable")
public record SaveSocialConnectionCommand(
    UUID organizationId,
    SocialProvider provider,
    boolean enabled,
    String clientId,
    String rawClientSecret,
    boolean blockEmailSubaddresses,
    boolean alwaysShowAccountSelector,
    AuditActor actor) {}
