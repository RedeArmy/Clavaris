package com.clavaris.clientregistry.application.usecase.updateoauthclientredirectsettings;

/** Inbound port for the dashboard's own "Redirect settings" form on an OAuthClient's own page. */
@FunctionalInterface
public interface UpdateOAuthClientRedirectSettingsUseCase {

  void handle(UpdateOAuthClientRedirectSettingsCommand command);
}
