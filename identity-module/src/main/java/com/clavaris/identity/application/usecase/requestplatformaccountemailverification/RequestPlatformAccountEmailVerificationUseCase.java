package com.clavaris.identity.application.usecase.requestplatformaccountemailverification;

@FunctionalInterface
public interface RequestPlatformAccountEmailVerificationUseCase {

  /**
   * @throws UnknownPlatformAccountException if {@code command.platformAccountId()} doesn't resolve
   */
  void handle(RequestPlatformAccountEmailVerificationCommand command);
}
