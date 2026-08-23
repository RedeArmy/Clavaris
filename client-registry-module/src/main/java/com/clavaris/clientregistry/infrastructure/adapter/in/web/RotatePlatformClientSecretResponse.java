package com.clavaris.clientregistry.infrastructure.adapter.in.web;

import com.clavaris.clientregistry.application.usecase.rotateplatformclientsecret.RotatePlatformClientSecretResult;

public record RotatePlatformClientSecretResponse(String clientId, String clientSecret) {

  public static RotatePlatformClientSecretResponse from(
      final RotatePlatformClientSecretResult result) {
    return new RotatePlatformClientSecretResponse(result.clientId(), result.rawSecret());
  }
}
