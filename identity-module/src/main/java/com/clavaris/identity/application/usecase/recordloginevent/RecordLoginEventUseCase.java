package com.clavaris.identity.application.usecase.recordloginevent;

/**
 * TD-FUT-034, Clerk activity heatmap parity — records one successful tenant sign-in. Never throws
 * (see {@link RecordLoginEventService}'s own Javadoc) — a side-channel bookkeeping failure must
 * never fail an otherwise-successful login, same guarantee {@code RecordAccountLoginDeviceUseCase}
 * already establishes for its own, adjacent concern.
 */
@FunctionalInterface
public interface RecordLoginEventUseCase {

  void handle(RecordLoginEventCommand command);
}
