package com.clavaris.identity.application.usecase.requestplatformaccountemailverification;

import com.clavaris.identity.domain.model.PlatformAccountId;

public record RequestPlatformAccountEmailVerificationCommand(PlatformAccountId platformAccountId) {}
