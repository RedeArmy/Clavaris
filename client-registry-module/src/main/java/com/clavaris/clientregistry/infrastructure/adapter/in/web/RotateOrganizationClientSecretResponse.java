package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.rotateorganizationclientsecret.RotateOrganizationClientSecretResult;

public record RotateOrganizationClientSecretResponse(String clientId, String clientSecret) {

  public static RotateOrganizationClientSecretResponse from(
      final RotateOrganizationClientSecretResult result) {
    return new RotateOrganizationClientSecretResponse(result.clientId(), result.rawSecret());
  }
}
