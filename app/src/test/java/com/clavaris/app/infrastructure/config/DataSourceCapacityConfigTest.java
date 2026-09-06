package com.clavaris.app.infrastructure.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.clavaris.app.support.RedisBackedIntegrationTest;
import com.zaxxer.hikari.HikariDataSource;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;

/**
 * TD-PERF-007: proves the real, resolved HikariCP configuration this app actually boots with —
 * application.yml's own {@code spring.datasource.hikari.*} block, not a hardcoded literal — the
 * same "load the real file, don't trust a property name is spelled right" discipline {@code
 * TaskSchedulingPoolSizeTest} already establishes for TD-PERF-003's own config.
 *
 * <p>No dedicated test for {@code server.tomcat.threads.max}: every {@code @SpringBootTest} in this
 * module with {@code webEnvironment = RANDOM_PORT} (this class included) already boots a real
 * embedded Tomcat off this exact config and serves real HTTP traffic through it — a wrong or
 * unbound property there would fail Tomcat's own startup, not silently do nothing, so a dedicated
 * assertion on the connector's own thread-pool internals would be testing Tomcat's binding
 * machinery more than this project's own code.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DataSourceCapacityConfigTest extends RedisBackedIntegrationTest {

  @Container @ServiceConnection
  static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

  @Autowired private DataSource dataSource;

  @Test
  void hikariPoolIsSizedAndTimedOutAsConfigured() {
    final HikariDataSource hikari = (HikariDataSource) dataSource;

    assertThat(hikari.getMaximumPoolSize()).isEqualTo(10);
    assertThat(hikari.getConnectionTimeout()).isEqualTo(10000);
  }
}
