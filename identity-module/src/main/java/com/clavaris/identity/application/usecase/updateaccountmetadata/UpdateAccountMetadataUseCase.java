package com.clavaris.identity.application.usecase.updateaccountmetadata;

/**
 * TD-FUT-034, Clerk "Metadata" parity — replaces all three of an Account's metadata tiers
 * (public/private/unsafe) at once.
 */
@FunctionalInterface
public interface UpdateAccountMetadataUseCase {

  /**
   * @throws AccountNotFoundException if {@code command.accountId()} doesn't exist
   * @throws InvalidMetadataException if a non-blank tier isn't syntactically valid JSON, or exceeds
   *     the configured size limit
   */
  void handle(UpdateAccountMetadataCommand command);
}
