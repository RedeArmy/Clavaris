package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Path;
import java.time.Duration;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.FileSystemResource;

/**
 * TD-PROC-029: the application's shutdown sequence and the container's patience have to agree. If
 * Docker's {@code stop_grace_period} is shorter than the drain plus the wait for a running job, the
 * container is killed mid-shutdown and the work those two settings protect is lost anyway, so the
 * three numbers are read from the files that carry them and compared.
 */
class ShutdownWindowTest {

  private static Properties yaml(final org.springframework.core.io.Resource resource) {
    final YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(resource);
    return yaml.getObject();
  }

  private static Duration duration(final Properties properties, final String key) {
    final String value = properties.getProperty(key);
    assertThat(value).as(key + " must be set").isNotNull();
    return DurationStyle.detectAndParse(value);
  }

  @Test
  void theContainerWaitsLongerThanTheDrainPlusTheWaitForARunningJob() {
    final Properties application = yaml(new ClassPathResource("application.yml"));
    // cwd is the module directory under Maven, so the compose file is one level up.
    final Properties compose =
        yaml(new FileSystemResource(Path.of("..", "docker-compose.prod.yml")));

    assertThat(application.getProperty("server.shutdown")).isEqualTo("graceful");
    assertThat(application.getProperty("spring.task.scheduling.shutdown.await-termination"))
        .isEqualTo("true");
    final Duration drain = duration(application, "spring.lifecycle.timeout-per-shutdown-phase");
    final Duration jobs =
        duration(application, "spring.task.scheduling.shutdown.await-termination-period");
    final Duration grace = duration(compose, "services.app.stop_grace_period");

    assertThat(grace)
        .as("stop_grace_period must exceed drain (%s) + job wait (%s)", drain, jobs)
        .isGreaterThan(drain.plus(jobs));
  }
}
