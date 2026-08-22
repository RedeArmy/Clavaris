package com.clavaris.identity.application.usecase.requestplatformaccountpasswordreset;

import com.clavaris.identity.domain.model.Email;

public record RequestPlatformAccountPasswordResetCommand(Email email) {}
