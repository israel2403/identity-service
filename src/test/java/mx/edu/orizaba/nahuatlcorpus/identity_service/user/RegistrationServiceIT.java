package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** RegistrationServiceIT */
@SpringBootTest("test")
@ActiveProfiles
@Import(RegistrationServiceIT.DatabaseConfiguration.class)
public class RegistrationServiceIT {

  @Autowired RegistrationService registration;
  @Autowired UserAccountWriter writer;
  @Autowired UserAccountRepository repository;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;

  @BeforeEach
  void reset() {
    repository.deleteAllInBatch();
  }

  private Map<String, Object> request(String email) {
    return new HashMap<>(
        Map.of(
            "fullname", "\u00a0María Ācatl\u00a0",
            "email", email,
            "password", "  Synthetic integration café 🔐 42!  "));
  }

  @Test
  void normalizesHashesAndCommits() {
    var attributes = request(" Person@Example.ORG ");
    var result = registration.register(attributes);
    assertEquals("María Ācatl", result.fullname());
    assertEquals("person@example.org", result.email());
    assertNotNull(result.id());
    assertEquals(1L, repository.count());
    String stored =
        jdbc.queryForObject(
            "SELECT password_hash FROM identity_service.user_accounts WHERE id = ?",
            String.class,
            result.id());
    String raw = (String) attributes.get("password");
    assertFalse(raw.equals(stored), "Plaintext must not be stored");
    assertTrue(stored.startsWith("{argon2id-v1}"), "Configured encoding ID required");
    assertTrue(encoder.matches(raw, stored), "Original password must verify");
    assertFalse(encoder.matches(raw.strip(), stored), "Password edge spaces matter");
    assertFalse(
        encoder.matches(raw.replace("é", "e\u0301"), stored),
        "Unicode normalization must not be applied");
  }

  @Test
  void translatesCaseAndWhitespaceVariantDuplicate() {
    registration.register(request("person@example.org"));
    var failure =
        assertThrows(
            DuplicateEmailException.class,
            () -> registration.register(request(" Person@Example.ORG ")));
    assertNull(failure.getCause(), "Database details must not escape in duplicate cause");
    assertEquals(1L, repository.count());
    registration.register(request("another@example.org"));
    assertEquals(2L, repository.count());
  }

  @Test
  void invalidInputCreatesNoAccount() {
    var attributes = request("not-an-email");
    attributes.put("fullname", " ");
    attributes.put("password", "short");
    var failure =
        assertThrows(
            RegistrationValidationException.class, () -> registration.register(attributes));
    assertEquals(3, failure.violations().size());
    assertEquals(0L, repository.count());
  }

  @Test
  void unrelatedDatabaseConstraintIsNotDuplicateEmail() {
    var failure =
        assertThrows(
            DataIntegrityViolationException.class,
            () ->
                writer.create(
                    " ",
                    "invalid@example.org",
                    encoder.encode("Synthetic classifier password 42!")));
    assertFalse(EmailConstraintClassifier.isDuplicate(failure));
    assertEquals(0L, repository.count());
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class DatabaseConfiguration {
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {
      return new PostgreSQLContainer("postgres:17-alpine");
    }
  }

  @Test
  void classifiesRealEmailDuplicate() {
    String encoded = encoder.encode("Synthetic classifier password 42!");
    writer.create("First", "duplicate@example.org", encoded);

    var failure =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> writer.create("Second", "duplicate@example.org", encoded));

    assertTrue(EmailConstraintClassifier.isDuplicate(failure));
    assertEquals(1L, repository.count());
  }

  @Test
  void concurrentNormalizaedDuplicatesHaveOneWinner() throws Exception {
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);

    try {
      Callable<Boolean> firstAttempt =
          () -> attemptRetistration("Person@Example.org", ready, start);
      Callable<Boolean> secondAttempt =
          () -> attemptRetistration(" person@example.org ", ready, start);

      var first = pool.submit(firstAttempt);
      var second = pool.submit(secondAttempt);

      assertTrue(ready.await(10, TimeUnit.SECONDS), "Workers must be ready");
      start.countDown();

      boolean firstWon = first.get(20, TimeUnit.SECONDS);
      boolean secondWon = second.get(20, TimeUnit.SECONDS);

      assertTrue(firstWon != secondWon, "Exactly one registration must succeed");
      assertEquals(1L, repository.count());
      assertEquals(
          1L,
          jdbc.queryForObject(
              """
        SELECT count(*)
        FROM identity_service.user_accounts
        WHERE email = ?
        """,
              Long.class,
              "person@example.org"));
    } finally {

      start.countDown();
      pool.shutdown();
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "Workers must terminate");
    }
  }

  public boolean attemptRetistration(String email, CountDownLatch ready, CountDownLatch start)
      throws Exception {
    ready.countDown();
    if (!start.await(10, TimeUnit.SECONDS)) {
      throw new AssertionError("Start coordination timed out");
    }
    try {
      registration.register(request(email));
      return true;
    } catch (DuplicateEmailException failure) {
      assertNull(failure.getCause(), "Database details must not escape");
      return false;
    }
  }
}
