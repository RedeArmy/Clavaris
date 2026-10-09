package com.clavaris.organization.infrastructure.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.validation.BeanPropertyBindingResult;

class SessionPolicyFormValidatorTest {

  private static final String LIFETIME = "maximumLifetimeValue";
  private static final String INACTIVITY = "inactivityTimeoutValue";
  private static final String REVERIFICATION = "reverificationWindowMinutes";

  private final SessionPolicyFormValidator validator = new SessionPolicyFormValidator();

  private static SetSessionPolicyForm form(
      final String lifetime,
      final String lifetimeUnit,
      final String inactivity,
      final String inactivityUnit,
      final String reverification) {
    SetSessionPolicyForm form = new SetSessionPolicyForm();
    form.setMaximumLifetimeValue(lifetime);
    form.setMaximumLifetimeUnit(lifetimeUnit);
    form.setInactivityTimeoutValue(inactivity);
    form.setInactivityTimeoutUnit(inactivityUnit);
    form.setReverificationWindowMinutes(reverification);
    return form;
  }

  private BeanPropertyBindingResult check(final SetSessionPolicyForm form) {
    BeanPropertyBindingResult errors = new BeanPropertyBindingResult(form, "form");
    validator.validate(form, errors);
    return errors;
  }

  private static String message(final BeanPropertyBindingResult errors, final String field) {
    return errors.getFieldError(field).getDefaultMessage();
  }

  @Test
  void anAmountAndUnitBecomeMinutes() {
    BeanPropertyBindingResult errors = new BeanPropertyBindingResult(new Object(), "form");

    Optional<SessionPolicyFormValidator.Parsed> parsed =
        validator.validate(form("18", "HOURS", "30", "MINUTES", "5"), errors);

    assertThat(errors.hasErrors()).isFalse();
    assertThat(parsed).contains(new SessionPolicyFormValidator.Parsed(1_080, 30, 5));
  }

  @Test
  void theBoundsAreInclusiveInEveryUnit() {
    assertThat(check(form("5", "MINUTES", "5", "MINUTES", "1")).hasErrors()).isFalse();
    assertThat(check(form("10", "YEARS", "1", "YEARS", "10")).hasErrors()).isFalse();
    assertThat(check(form("3650", "DAYS", "365", "DAYS", "10")).hasErrors()).isFalse();
    assertThat(check(form("521", "WEEKS", "52", "WEEKS", "10")).hasErrors()).isFalse();
    assertThat(check(form("120", "MONTHS", "12", "MONTHS", "10")).hasErrors()).isFalse();
  }

  @Test
  void aBlankOrNonNumericAmountIsNotAnAmount() {
    for (String raw : new String[] {null, "", "   ", "abc", "1.5", "1,5", "-3", "+5", "5h", "0"}) {
      BeanPropertyBindingResult errors = check(form(raw, "HOURS", "1", "DAYS", "5"));

      assertThat(message(errors, LIFETIME))
          .as("amount %s", raw)
          .isEqualTo("Enter a whole number greater than zero.");
    }
  }

  @Test
  void anAmountTooLongToBeRealIsRefusedRatherThanOverflowing() {
    BeanPropertyBindingResult errors = check(form("9999999999", "YEARS", "1", "DAYS", "5"));

    assertThat(message(errors, LIFETIME)).isEqualTo("Enter a whole number greater than zero.");
  }

  @Test
  void aMissingOrUnknownUnitIsRefused() {
    assertThat(message(check(form("5", null, "1", "DAYS", "5")), LIFETIME))
        .isEqualTo("Choose a unit of time.");
    assertThat(message(check(form("5", "FORTNIGHTS", "1", "DAYS", "5")), LIFETIME))
        .isEqualTo("Choose a unit of time.");
  }

  @Test
  void aTooShortDurationNamesTheMinimumInItsLargestExactUnit() {
    assertThat(message(check(form("4", "MINUTES", "1", "DAYS", "5")), LIFETIME))
        .isEqualTo("Must be at least 5 minutes.");
    assertThat(message(check(form("1", "WEEKS", "1", "MINUTES", "5")), INACTIVITY))
        .isEqualTo("Must be at least 5 minutes.");
    assertThat(message(check(form("1", "WEEKS", "1", "DAYS", "0")), REVERIFICATION))
        .isEqualTo("Enter a whole number greater than zero.");
  }

  @Test
  void aTooLongDurationIsJudgedAfterTheUnitIsApplied() {
    assertThat(message(check(form("11", "YEARS", "1", "DAYS", "5")), LIFETIME))
        .isEqualTo("Must be at most 10 years.");
    assertThat(message(check(form("3651", "DAYS", "1", "DAYS", "5")), LIFETIME))
        .isEqualTo("Must be at most 10 years.");
    assertThat(message(check(form("10", "YEARS", "2", "YEARS", "5")), INACTIVITY))
        .isEqualTo("Must be at most 1 year.");
    assertThat(message(check(form("1", "WEEKS", "1", "DAYS", "11")), REVERIFICATION))
        .isEqualTo("Must be at most 10 minutes.");
  }

  @Test
  void aSmallNumberInALargeUnitCanStillBeTooLong() {
    // 6000 minutes is fine; 6000 weeks is over a century.
    assertThat(check(form("6000", "MINUTES", "1", "HOURS", "5")).hasErrors()).isFalse();
    assertThat(message(check(form("6000", "WEEKS", "1", "HOURS", "5")), LIFETIME))
        .isEqualTo("Must be at most 10 years.");
  }

  @Test
  void anInactivityTimeoutCannotOutlastTheSessionItself() {
    BeanPropertyBindingResult errors = check(form("1", "DAYS", "2", "DAYS", "5"));

    assertThat(errors.getFieldError(LIFETIME)).isNull();
    assertThat(message(errors, INACTIVITY)).isEqualTo("Can't be longer than the maximum lifetime.");
  }

  @Test
  void anInactivityTimeoutEqualToTheLifetimeIsFine() {
    assertThat(check(form("1", "WEEKS", "7", "DAYS", "5")).hasErrors()).isFalse();
  }

  @Test
  void everyProblemIsReportedAtOnceNotOneAtATime() {
    BeanPropertyBindingResult errors = check(form("abc", "HOURS", "12", "YEARS", "99"));

    assertThat(errors.getFieldErrorCount()).isEqualTo(3);
    assertThat(message(errors, LIFETIME)).isEqualTo("Enter a whole number greater than zero.");
    assertThat(message(errors, INACTIVITY)).isEqualTo("Must be at most 1 year.");
    assertThat(message(errors, REVERIFICATION)).isEqualTo("Must be at most 10 minutes.");
  }

  @Test
  void aBrokenLifetimeDoesNotAlsoBlameTheInactivityTimeoutForBeingLonger() {
    BeanPropertyBindingResult errors = check(form("4", "MINUTES", "1", "DAYS", "5"));

    assertThat(errors.getFieldErrorCount()).isEqualTo(1);
    assertThat(errors.getFieldError(INACTIVITY)).isNull();
  }
}
