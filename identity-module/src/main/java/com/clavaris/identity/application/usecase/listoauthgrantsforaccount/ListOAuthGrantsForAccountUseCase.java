package com.clavaris.identity.application.usecase.listoauthgrantsforaccount;

import java.util.List;

@FunctionalInterface
public interface ListOAuthGrantsForAccountUseCase {

  List<OAuthGrant> handle(ListOAuthGrantsForAccountQuery query);
}
