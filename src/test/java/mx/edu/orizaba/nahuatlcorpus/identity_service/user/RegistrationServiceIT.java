package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
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
@SpringBootTest
@ActiveProfiles("test")
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

  @Test
  void samePasswordForTwoAccountsProducesDifferentStoredEncodigns() {
    var firstRequest = request("first-storage@example.org");
    var secondRequest = request("second-storage@example.org");
    String raw = (String) firstRequest.get("password");
    secondRequest.put("password", raw);

    var first = registration.register(firstRequest);
    var second = registration.register(secondRequest);
    String firstStored = storedCredential(first.id());
    String secondStored = storedCredential(second.id());

    String policy = "{argon2id-v1}$argon2id$v=19$m=19456,t=2,p=1$";
    assertTrue(firstStored.startsWith(policy), "First credential must use the configured policy");
    assertTrue(secondStored.startsWith(policy), "Second credential must use the configured policy");
    assertFalse(raw.equals(firstStored), "First credential must not be plaintext");
    assertFalse(raw.equals(secondStored), "Second credential must be plaintext");
    assertFalse(
        firstStored.equals(secondStored), "Independent salts must produce distinct encodings");
    assertTrue(encoder.matches(raw, firstStored), "First stored encoding must verify");
    assertTrue(encoder.matches(raw, secondStored), "Second stored encoding must verify");
    assertFalse(
        encoder.matches("Different synthetic password 42!", firstStored),
        "Incorrect password must not verify");
    assertEquals(2L, repository.count());
  }

  @Test
  void supportsMaximumUnicodePasswordWithoutTruncation() {
    var attributes = request("maximum-storage@example.org");
    String raw = "🔐".repeat(128);
    attributes.put("password", raw);

    var created = registration.register(attributes);
    String stored = storedCredential(created.id());

    assertTrue(encoder.matches(raw, stored), "All 128 code points must verify");
    assertFalse(encoder.matches("🔐".repeat(127), stored), "A shortened password must not verify");
    assertEquals(1L, repository.count());
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

  @Test
  void registrationResultTextContainsNeitherRawNorEncodedCredential() {
    var attributes = request("save-result@example.org");
    String raw = (String) attributes.get("password");

    var created = registration.register(attributes);
    String stored = storedCredential(created.id());

    assertFalse(created.toString().contains(raw), "Result text must omit raw credentials");
    assertFalse(created.toString().contains(stored), "Result text must omit encodeded credentials");
  }

  private String storedCredential(UUID id) {
    String stored =
        jdbc.queryForObject(
            "SELECT password_hash FROM identity_service.user_accounts WHERE id = ?",
            String.class,
            id);
    assertNotNull(stored, "Committed account must have an encoded credential");
    return stored;
  }
}
