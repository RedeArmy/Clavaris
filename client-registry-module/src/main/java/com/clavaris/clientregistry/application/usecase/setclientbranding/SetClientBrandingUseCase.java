package com.clavaris.clientregistry.application.usecase.setclientbranding;

@FunctionalInterface
public interface SetClientBrandingUseCase {

  SetClientBrandingResult handle(SetClientBrandingCommand command);
}
