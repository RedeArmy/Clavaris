package com.clavaris.organization.infrastructure.adapter.in.web;

import com.clavaris.organization.domain.model.SessionPolicy;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.OptionalLong;
import java.util.regex.Pattern;
import org.springframework.validation.Errors;

/**
 * Turns what a person typed on the Sessions form (an amount and a unit for each duration) into the
 * minutes the use case takes, or says what is wrong.
 *
 * <p>The bounds are the domain's own ({@link SessionPolicy}'s public constants), checked after the
 * unit is applied: "11 years" fails the 10-year ceiling the same way {@code 5,781,600} minutes
 * would, and "30 seconds" cannot be entered at all. Each message names the bound in the largest
 * unit that states it exactly ("Must be at most 1 year."), so it reads the same whichever unit was
 * chosen and matches what the page's script says as the person types. The script is only a
 * convenience; this is the check that counts.
 *
 * <p>One rule beyond the domain's: the inactivity timeout may not exceed the maximum lifetime,
 * because a session ends at its maximum lifetime however active it is, so a longer inactivity
 * timeout could never take effect. It is a rule of this form, not of the domain or the REST API.
 */
@SuppressWarnings("PMD.AtLeastOneConstructor") // stateless; the implicit constructor is enough
final class SessionPolicyFormValidator {

  /* default */ static final String LIFETIME_FIELD = "maximumLifetimeValue";
  /* default */ static final String INACTIVITY_FIELD = "inactivityTimeoutValue";
  /* default */ static final String WINDOW_FIELD = "reverificationWindowMinutes";

  private static final Pattern WHOLE_NUMBER = Pattern.compile("\\d{1,9}");
  private static final String INVALID = "invalid";

  /** The three durations in minutes (lifetime, inactivity, reverification), all accepted. */
  /* default */ record Parsed(int lifetime, int inactivity, int reverification) {}

  /**
   * @return the durations in minutes, or empty after recording every problem on {@code errors}
   */
  /* default */ Optional<Parsed> validate(final SetSessionPolicyForm form, final Errors errors) {
    final OptionalInt lifetime =
        duration(
            errors,
            LIFETIME_FIELD,
            form.getMaximumLifetimeValue(),
            form.getMaximumLifetimeUnit(),
            SessionPolicy.MIN_MAXIMUM_LIFETIME_MINUTES,
            SessionPolicy.MAX_MAXIMUM_LIFETIME_MINUTES);
    final OptionalInt inactivity =
        duration(
            errors,
            INACTIVITY_FIELD,
            form.getInactivityTimeoutValue(),
            form.getInactivityTimeoutUnit(),
            SessionPolicy.MIN_INACTIVITY_TIMEOUT_MINUTES,
            SessionPolicy.MAX_INACTIVITY_TIMEOUT_MINUTES);
    final OptionalInt reverification =
        duration(
            errors,
            WINDOW_FIELD,
            form.getReverificationWindowMinutes(),
            DurationUnit.MINUTES.name(),
            SessionPolicy.MIN_REVERIFICATION_WINDOW_MINUTES,
            SessionPolicy.MAX_REVERIFICATION_WINDOW_MINUTES);

    if (lifetime.isPresent()
        && inactivity.isPresent()
        && inactivity.getAsInt() > lifetime.getAsInt()) {
      errors.rejectValue(INACTIVITY_FIELD, INVALID, "Can't be longer than the maximum lifetime.");
    }
    return errors.hasErrors()
        ? Optional.empty()
        : Optional.of(
            new Parsed(lifetime.getAsInt(), inactivity.getAsInt(), reverification.getAsInt()));
  }

  // Every problem is recorded on the amount field, so the page shows one message per duration.
  private static OptionalInt duration(
      final Errors errors,
      final String field,
      final String rawAmount,
      final String rawUnit,
      final int minimumMinutes,
      final int maximumMinutes) {
    OptionalInt minutes = OptionalInt.empty();
    final Optional<DurationUnit> unit = DurationUnit.parse(rawUnit);
    final OptionalLong amount = amount(rawAmount);
    if (unit.isEmpty()) {
      errors.rejectValue(field, INVALID, "Choose a unit of time.");
    } else if (amount.isEmpty()) {
      errors.rejectValue(field, INVALID, "Enter a whole number greater than zero.");
    } else {
      final long total = unit.get().toMinutes(amount.getAsLong());
      if (total < minimumMinutes) {
        errors.rejectValue(
            field, INVALID, "Must be at least " + DurationUnit.describe(minimumMinutes) + ".");
      } else if (total > maximumMinutes) {
        errors.rejectValue(
            field, INVALID, "Must be at most " + DurationUnit.describe(maximumMinutes) + ".");
      } else {
        minutes = OptionalInt.of((int) total);
      }
    }
    return minutes;
  }

  // A whole number of 1 to 9 digits (so it cannot overflow) and greater than zero; anything else,
  // including blank, a decimal, a sign or a unit typed into the number, is not an amount.
  private static OptionalLong amount(final String raw) {
    OptionalLong amount = OptionalLong.empty();
    if (raw != null && WHOLE_NUMBER.matcher(raw.strip()).matches()) {
      final long parsed = Long.parseLong(raw.strip());
      if (parsed > 0) {
        amount = OptionalLong.of(parsed);
      }
    }
    return amount;
  }
}
