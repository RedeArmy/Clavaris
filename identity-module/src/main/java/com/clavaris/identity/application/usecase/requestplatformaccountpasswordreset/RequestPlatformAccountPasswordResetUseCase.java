package com.clavaris.identity.application.usecase.requestplatformaccountpasswordreset;

/**
 * Same anti-enumeration "never observably distinguishes found from not-found" contract as {@code
 * requestpasswordreset.RequestPasswordResetUseCase} — see that interface's own Javadoc.
 */
@FunctionalInterface
public interface RequestPlatformAccountPasswordResetUseCase {

  void handle(RequestPlatformAccountPasswordResetCommand command);
}
