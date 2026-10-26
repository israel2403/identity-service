package mx.edu.orizaba.nahuatlcorpus.identity_service.config;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Base64;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.password.PasswordEncoder;

class PasswordEncodingConfigurationTest {
  private static final String ID_PREFIX = "{argon2id-v1}";
  private static final String POLICY_PREFIX = ID_PREFIX + "$argon2id$v=19$m=19456,t=2,p=1$";

  // Use the production factory, with no duplicated encoder configuration or mock.
  private final PasswordEncoder encoder = new PasswordEncodingConfiguration().passwordEncoder();

  @Test
  void verifiesOriginalPasswordAndRejectsIncorrectPassword() {
    String raw = "Only a synthetic test password 42!";
    String encoded = encoder.encode(raw);

    assertTrue(encoder.matches(raw, encoded), "Original password must verify");
    assertFalse(
        encoder.matches("A different synthetic password 42!", encoded),
        "Incorrect password must be rejected");
  }

  @Test
  void encodesWithTheRequiredIdAlgorithmVersionAndParameters() {
    String encoded = encoder.encode("Synthetic encoding policy fixture 42!");

    assertTrue(encoded.startsWith(POLICY_PREFIX), "Encoding policy must match");
    String[] fields = encoded.substring(ID_PREFIX.length()).split("\\$", -1);
    assertTrue(fields.length == 6, "PHC representation must have six fields");
    assertTrue(Base64.getDecoder().decode(fields[4]).length == 16, "Salt must contain 16 bytes");
    assertTrue(
        Base64.getDecoder().decode(fields[5]).length == 32, "Derived hash must contain 32 bytes");
  }

  @Test
  void generateDifferentEncodingsForTheSamePassword() {
    String raw = "Synthetic random salt fixture 42!";
    String first = encoder.encode(raw);
    String second = encoder.encode(raw);

    assertFalse(first.equals(second), "Independent encoder must differ");
    assertTrue(encoder.matches(raw, first), "First enconding must verify");
    assertTrue(encoder.matches(raw, second), "Second encoding must verify");
  }

  @Test
  void preservesUnicodeWithoutNormalization() {
    // Visually similar strings, composed vs decomposed accented character.
    String raw = "Synthetic caf\u00e9 password \uD83D\uDD10 42!";
    String decomposed = "Synthetic cafe\u0301 password \uD83D\uDD10 42!";
    String encoded = encoder.encode(raw);

    assertTrue(encoder.matches(raw, encoded), "Exact Unicode password must verify");
    assertFalse(
        encoder.matches(decomposed, encoded),
        "Unicode normalization must not change the password silently");
  }

  @Test
  void preservesMeaningfulLeadingAndTrailingSpaces() {
    String raw = "  Synthetic whitespace fixture 42!  ";
    String encoded = encoder.encode(raw);

    assertTrue(encoder.matches(raw, encoded), "Exact whitespace must verify");
    assertFalse(encoder.matches(raw.stripLeading(), encoded), "Leading spaces matter");
    assertFalse(encoder.matches(raw.stripTrailing(), encoded), "Trailing spaces matter");
    assertFalse(encoder.matches(raw.strip(), encoded), "Both boundaries matter");
  }

  @Test
  void rejectsUnknownAndMissingIdsEvenWithAValidArgon2Payload() {
    String raw = "Synthetic rejected ID fixture 42!";
    String encoded = encoder.encode(raw);

    assertTrue(encoded.startsWith(ID_PREFIX), "Configured encoding ID must match");
    String payload = encoded.substring(ID_PREFIX.length());

    assertThrows(
        IllegalArgumentException.class,
        () -> encoder.matches(raw, "{unregistered-id}" + payload),
        "Unknown ID must fail closed");
    assertThrows(
        IllegalArgumentException.class,
        () -> encoder.matches(raw, payload),
        "Missing ID must fail closed");
  }
}
