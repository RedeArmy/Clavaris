package com.clavaris.identity.application.usecase.rotatesigningkeyfororganization;

import com.clavaris.identity.domain.model.SigningKey;

public record RotateSigningKeyForOrganizationResult(SigningKey newKey, String previousKid) {}
