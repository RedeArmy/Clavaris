package com.clavaris.identity.application.usecase.listwebauthncredentialsforaccount;

import com.clavaris.identity.domain.model.WebAuthnCredential;
import java.util.List;

/**
 * Read-only, shared by both the self-service passkeys page and the admin "View Profile" page — same
 * dual-consumer precedent {@code GetLoginActivityForAccountUseCase} already sets for the heatmap.
 */
@FunctionalInterface
public interface ListWebAuthnCredentialsForAccountUseCase {

  List<WebAuthnCredential> handle(ListWebAuthnCredentialsForAccountQuery query);
}
