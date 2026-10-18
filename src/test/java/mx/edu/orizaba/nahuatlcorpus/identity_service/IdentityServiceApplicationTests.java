package mx.edu.orizaba.nahuatlcorpus.identity_service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(IdentityServiceApplicationTests.DatabaseConfiguration.class)
class IdentityServiceApplicationTests {

	@Autowired
	JdbcTemplate jdbc;

	@LocalServerPort
	int port;

	@Test
	void connectsToPostgresql() {
		assertThat(jdbc.queryForObject("SELECT 1", Integer.class)).isEqualTo(1);
		assertThat(jdbc.queryForObject("SELECT version()", String.class)).contains("PostgreSQL");
	}

	@Test
	void servesHealthyApplication() throws Exception {
		try (var client = HttpClient.newHttpClient()) {
			var request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health"))
					.GET().build();
			var response = client.send(request, HttpResponse.BodyHandlers.ofString());
			assertThat(response.statusCode()).isEqualTo(200);
			assertThat(response.body()).contains("\"status\":\"UP\"");
		}
	}

	@TestConfiguration(proxyBeanMethods = false)
	static class DatabaseConfiguration {
		@Bean
		@ServiceConnection
		PostgreSQLContainer postgres() {
			return new PostgreSQLContainer("postgres:17-alpine");
		}
	}
}
