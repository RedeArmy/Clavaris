package com.clavaris.identity.application.usecase.requestemailchange;

/** The Organization has switched the End-User's own email change off (TD-FUT-052). */
public class EmailChangeNotAllowedException extends RuntimeException {

  private static final long serialVersionUID = 1L;

  public EmailChangeNotAllowedException() {
    super("Changing the email is not allowed for this organization");
  }
}
