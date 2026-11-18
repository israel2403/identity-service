package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.hibernate.exception.ConstraintViolationException;
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

/** UserAccountPersistenceIT */
@SpringBootTest
@ActiveProfiles("test")
@Import(UserAccountPersistenceIT.DatabaseConfiguration.class)
public class UserAccountPersistenceIT {

  private static final String RAW = "Synthetic persistance password 42!";

  @Autowired UserAccountWriter writer;
  @Autowired UserAccountRepository repository;
  @Autowired JdbcTemplate jdbc;
  @Autowired PasswordEncoder encoder;

  private String encoded;

  @BeforeEach
  void reset() {
    repository.deleteAllInBatch();
    encoded = encoder.encode(RAW);
  }

  @Test
  void appliesBothMigrationsAndValidatesMapping() {
    assertEquals(
        2,
        jdbc.queryForObject(
            "SELECT count(*) FROM flyway_schema_history WHERE success = true", Integer.class));
    assertEquals(0L, repository.count());
  }

  @Test
  void commitsAccountWithEncodedCredentialAndSafeResult() {
    var created = writer.create("  María Ācatl  ", "created@example.org", encoded);
    assertNotNull(created.id());
    assertEquals("  María Ācatl  ", created.fullname());
    assertEquals("created@example.org", created.email());
    assertEquals(1L, repository.count());
    var persisted = repository.findByEmail(created.email()).orElseThrow();
    assertEquals(created.id(), persisted.getId());
    assertEquals(created.fullname(), persisted.getFullname());
    assertEquals(created.email(), persisted.getEmail());
    String stored =
        jdbc.queryForObject(
            "SELECT password_hash FROM identity_service.user_accounts WHERE id = ?",
            String.class,
            created.id());
    // Fixed messages and boolean assertions avoid printing credential values
    assertFalse(RAW.equals(stored), "Plain text must not be stored");
    assertTrue(encoded.equals(stored), "Complete encoded value must be preserve");
    assertTrue(encoder.matches(RAW, stored), "Stored credentials must verified");
    assertEquals(
        List.of("id", "fullname", "email"),
        Arrays.stream(CreatedUser.class.getRecordComponents()).map(c -> c.getName()).toList());
  }

  @Test
  void rejectsDuplicateCanonicalEmailAndKeepsOneComittedAccount() {
    writer.create("First", "duplicate@example.org", encoded);
    var failure =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> writer.create("Second", "duplicate@example.org", encoded));
    assertTrue(isConstraint(failure, "23505", "uq_user_accounts_email"));
    assertEquals(1L, repository.count());
  }

  public static boolean isConstraint(Throwable failure, String state, String name) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation
          && state.equals(violation.getSQLState())
          && name.equals(violation.getConstraintName())) {
        return true;
      }
    }
    return false;
  }

  @Test
  void rollsBackInvalidInsertAndAllowsNextTransaction() {
    var failure =
        assertThrows(
            DataIntegrityViolationException.class,
            () -> writer.create("   ", "invalid@example.org", encoded));

    assertTrue(isConstraint(failure, "23514", "ck_user_accounts_fullname_nonblank"));
    assertFalse(isConstraint(failure, "23505", "uq_user_accounts_email"));
    assertEquals(0L, repository.count());
    assertTrue(repository.findByEmail("invalid@example.org").isEmpty());
    writer.create("Valid", "invalid@example.org", encoded);
    assertEquals(1L, repository.count());
  }

  @Test
  void rejectsNoncanonicalEmailAndBlankHash() {
    assertThrows(
        DataIntegrityViolationException.class,
        () -> writer.create("Name", "Mixed@Example.org", encoded));
    assertThrows(
        DataIntegrityViolationException.class,
        () -> writer.create("Name", "blank-hash@example.org", " "));
    assertEquals(0L, repository.count());
  }

  @Test
  void concurrentDuplicatesHaveOneWinnerAndOneUniqueConstraintFailure() throws Exception {
    var ready = new CountDownLatch(2);
    var start = new CountDownLatch(1);
    var pool = Executors.newFixedThreadPool(2);
    try {
      Callable<Boolean> attempt =
          () -> {
            ready.countDown();
            if (!start.await(10, TimeUnit.SECONDS)) {
              throw new AssertionError("Start coordination timed out");
            }
            try {
              writer.create("Concurrent", "concurrent@example.org", encoded);
              return true;
            } catch (DataIntegrityViolationException failure) {
              assertTrue(isConstraint(failure, "23505", "uq_user_accounts_email"));
              return false;
            }
          };
      var first = pool.submit(attempt);
      var second = pool.submit(attempt);
      assertTrue(ready.await(10, TimeUnit.SECONDS), "Workers must be ready");
      start.countDown();
      boolean firstWon = first.get(20, TimeUnit.SECONDS);
      boolean secondWon = second.get(20, TimeUnit.SECONDS);
      assertTrue(firstWon != secondWon, "Exactly one transaction must succeed");
      assertEquals(1L, repository.count());
      assertTrue(repository.findByEmail("concurrent@example.org").isPresent());
    } finally {
      start.countDown();
      pool.shutdownNow();
      assertTrue(pool.awaitTermination(10, TimeUnit.SECONDS), "Workers must terminate");
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
