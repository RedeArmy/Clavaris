package com.clavaris.identity.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import org.junit.jupiter.api.Named;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mockito;
import org.springframework.context.annotation.Bean;

/**
 * Builds every bean these use-case configurations declare, with mocked collaborators. The wiring
 * (which service gets which port, and since TD-ARCH-052 which clock) is otherwise exercised only by
 * the {@code app} module's integration tests, which do not count towards this module's coverage and
 * which start a whole application to find a mistyped argument. A constructor that rejects what it
 * is given, or a bean method that no longer matches its service, fails here, in milliseconds.
 */
class UseCaseConfigBeansTest {

  static Stream<Named<Class<?>>> configurations() {
    return Stream.of(
            AuthenticationUseCaseConfig.class,
            CredentialRecoveryUseCaseConfig.class,
            IdentityUseCaseConfig.class,
            PasswordlessAndSocialUseCaseConfig.class,
            SelfServiceCredentialUseCaseConfig.class,
            WebAuthnUseCaseConfig.class)
        .map(type -> Named.<Class<?>>of(type.getSimpleName(), type));
  }

  @ParameterizedTest
  @MethodSource("configurations")
  void everyBeanMethodBuildsItsBean(final Class<?> configuration) throws Exception {
    final Object instance = construct(configuration);
    final List<String> failures = new ArrayList<>();
    int built = 0;
    for (final Method method : configuration.getDeclaredMethods()) {
      if (!method.isAnnotationPresent(Bean.class)) {
        continue;
      }
      method.setAccessible(true);
      try {
        final Object bean = method.invoke(instance, arguments(method));
        assertThat(bean).as(method.getName()).isNotNull();
        built++;
      } catch (final ReflectiveOperationException | RuntimeException failure) {
        final Throwable cause = failure.getCause() == null ? failure : failure.getCause();
        failures.add(method.getName() + ": " + cause);
      }
    }
    assertThat(failures).as("beans that could not be built").isEmpty();
    assertThat(built).as("bean methods found on " + configuration.getSimpleName()).isPositive();
  }

  private static Object construct(final Class<?> configuration) throws Exception {
    final var constructor = configuration.getDeclaredConstructors()[0];
    constructor.setAccessible(true);
    return constructor.newInstance(arguments(constructor.getParameters()));
  }

  private static Object[] arguments(final Method method) {
    return arguments(method.getParameters());
  }

  private static Object[] arguments(final Parameter[] parameters) {
    final Object[] values = new Object[parameters.length];
    for (int i = 0; i < parameters.length; i++) {
      values[i] = valueFor(parameters[i].getType());
    }
    return values;
  }

  // Plain values for the @Value parameters, a mock for everything else.
  private static Object valueFor(final Class<?> type) {
    if (type == int.class || type == Integer.class) {
      return 1;
    }
    if (type == long.class || type == Long.class) {
      return 1L;
    }
    if (type == boolean.class || type == Boolean.class) {
      return false;
    }
    if (type == String.class) {
      return "x";
    }
    if (type == Duration.class) {
      return Duration.ofMinutes(1);
    }
    if (type == Clock.class) {
      return Clock.systemUTC();
    }
    return Mockito.mock(type);
  }
}
