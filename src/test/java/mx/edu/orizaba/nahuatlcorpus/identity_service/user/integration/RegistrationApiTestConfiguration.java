package mx.edu.orizaba.nahuatlcorpus.identity_service.user.integration;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** RegistrationApiTestConfiguration */
@TestConfiguration(proxyBeanMethods = false)
public class RegistrationApiTestConfiguration {
  @Bean
  @ServiceConnection
  PostgreSQLContainer registrationPostgres() {
    return new PostgreSQLContainer("postgres:17-alpine");
  }
}
