package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** RegistrationValidatorTest */
public class RegistrationValidatorTest {

  private final RegistrationValidator validator = new RegistrationValidator();

  private Map<String, Object> valid() {
    return new HashMap<>(
        Map.of(
            "fullname",
            "María Ācatl",
            "email",
            "person@example.org",
            "password",
            "Synthetic password 42!"));
  }

  @Test
  void normalizesOnlyNameAndEmail() {
    var attributes = valid();
    String raw = "  Synthetic café password 🔐 42!  ";
    attributes.put("fullname", "\u00a0María  Ācatl\u2007");
    attributes.put("email", "\t Person@Example.ORG \r\n");
    attributes.put("password", raw);

    var result = validator.validate(attributes);
    assertEquals("María  Ācatl", result.fullName());
    assertEquals("person@example.org", result.email());
    assertFalse(result.toString().contains(raw), "String must be redacted");
    assertTrue(raw.equals(result.pasword()), "Password must remain unchanged");
  }

  @Test
  void reportsAllMissingFields() {
    var failure =
        assertThrows(RegistrationValidationException.class, () -> validator.validate(Map.of()));
    assertEquals(
        Set.of("fullname", "email", "password"),
        failure.violations().stream()
            .map(RegistrationViolation::field)
            .collect(Collectors.toSet()));
    assertTrue(
        failure.violations().stream().allMatch(v -> "REQUIRED_FIELD".equals(v.code())),
        "Every missing field must use REQUIRED_FIELD");
  }

  @Test
  void distinguishesNullWrongTypeAndInvalidString() {
    var attributes = valid();
    attributes.put("fullname", null);
    attributes.put("email", 42);
    attributes.put("password", " ".repeat(15));
    var failure =
        assertThrows(RegistrationValidationException.class, () -> validator.validate(attributes));
    assertEquals(
        Set.of(
            new RegistrationViolation("fullname", "INVALID_TYPE"),
            new RegistrationViolation("email", "INVALID_TYPE"),
            new RegistrationViolation("password", "INVALID_VALUE")),
        Set.copyOf(failure.violations()));
  }

  @Test
  void usesCodePointsForNameAndPasswordBoundaries() {
    var attributes = valid();
    attributes.put("fullname", "Ā".repeat(100));
    attributes.put("password", "🔐".repeat(128));

    assertDoesNotThrow(() -> validator.validate(attributes));
    attributes.put("password", "🔐".repeat(129));
    attributes.put("fullname", "Ā".repeat(101));
    var failure =
        assertThrows(RegistrationValidationException.class, () -> validator.validate(attributes));
    assertEquals(2, failure.violations().size());
    var tooShort = valid();
    tooShort.put("password", "🔐".repeat(14));
    assertThrows(RegistrationValidationException.class, () -> validator.validate(tooShort));
  }

  @Test
  void checkEveryMandatoryFieldAndType() {
    for (String field : List.of("fullname", "email", "password")) {
      var missing = valid();
      missing.remove(field);

      var failure =
          assertThrows(RegistrationValidationException.class, () -> validator.validate(missing));

      assertEquals(
          List.of(new RegistrationViolation(field, "REQUIRED_FIELD")), failure.violations());

      assertViolation(field, null, "INVALID_TYPE");

      for (Object value : List.of(42, true, List.of("value", Map.of("key", "value")))) {
        assertViolation(field, value, "INVALID_TYPE");
      }

      assertViolation(field, "", "INVALID_VALUE");
      assertViolation(field, " ", "INVALID_VALUE");
    }
  }

  @Test
  void acceptsfnameBoundariesAndSupplementaryCharacters() {
    for (String name : List.of("Ā", "Ā".repeat(100), "🔐", "🔐".repeat(100))) {
      var attributes = valid();
      attributes.put("fullname", name);
      assertEquals(name, validator.validate(attributes).fullName());

      assertViolation("fullname", "🔐".repeat(101), "INVALID_VALUE");
    }
  }

  @Test
  void rejectsConstrolsAndUnpairedSurrogatesInName() {
    for (String name :
        List.of("María\nĀcatl", "María\tĀcatl", "María\u0000Ācatl", "\uD800", "\uDC00")) {
      assertViolation("fullname", name, "INVALID_VALUE");
    }
  }

  @Test
  void rejectsMalformedEmails() {
    for (String email :
        List.of(
            "person name@example.org",
            "person\tname@example.org",
            "\u00a0person@example.org",
            "person@example.org\u2007",
            "pérson@example.org",
            "person@@example.org",
            "person..name@example.org",
            ".person@example.org",
            "person.@example.org",
            "person@example",
            "person@example..org",
            "person@.example.org",
            "person@example.org.",
            "person@-example.org",
            "person@example-.org",
            "\"person\"@example.org",
            "person@[127.0.0.1]")) {
      assertViolation("email", email, "INVALID_VALUE");
    }
  }

  @Test
  void checksEmailLengthBoundaries() {
    String local64 = "a".repeat(64);
    String label63 = "b".repeat(63);

    String address254 = local64 + "@" + label63 + "." + label63 + "." + "c".repeat(61);
    assertEquals(254, address254.length());

    for (String email :
        List.of(local64 + "@example.org", "person@" + label63 + ".org", address254)) {
      var attributes = valid();
      attributes.put("email", email);
      assertEquals(email, validator.validate(attributes).email());
    }
  }

  @Test
  void lowercasesEmailIndependentlyOfDefaultLocale() {
    Locale original = Locale.getDefault();
    try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"));

      var attributes = valid();
      attributes.put("email", " \tI@EXAMPLE.ORG\r\n");

      assertEquals("i@example.org", validator.validate(attributes).email());
    } finally {
      Locale.setDefault(original);
    }
  }

  @Test
  void checksPasswordBoundariesWithoutChangingContent() {
    for (String password :
        List.of(
            "x".repeat(15),
            "x".repeat(128),
            "🔐".repeat(15),
            "🔐".repeat(128),
            "  Synthetic café password 🔐 42!  ",
            "  Synthetic cafe\u0301 password 🔐 42!  ")) {
      var attributes = valid();
      attributes.put("password", password);

      assertTrue(
          password.equals(validator.validate(attributes).pasword()),
          "Password content must remain unchanged");
    }

    for (String password :
        List.of(
            "x".repeat(14),
            "x".repeat(129),
            "🔐".repeat(14),
            "🔐".repeat(129),
            "\u00a0\u2007\u202f".repeat(5),
            "Synthetic password \uD800",
            "Synthetic password \uDC00")) {
      assertViolation("password", password, "INVALID_VALUE");
    }
  }

  @Test
  void validationErrorsDoNotExposeRejectedValues() {
    String rejected = "synthetic-rejected-secret";
    var attributes = valid();
    attributes.put("fullname", rejected + "\nName");
    attributes.put("email", rejected);
    attributes.put("password", rejected + "\uD800");

    var failure =
        assertThrows(RegistrationValidationException.class, () -> validator.validate(attributes));

    assertEquals(3, failure.violations().size());
    assertNull(failure.getCause());
    assertFalse(failure.getMessage().contains(rejected), "Message must omit rejected values");
    assertFalse(failure.toString().contains(rejected), "Exception text must omit rejected values");
    assertFalse(
        failure.violations().toString().contains(rejected), "Violations must omit rejected values");
  }

  private void assertViolation(String field, Object value, String code) {
    var attributes = valid();
    attributes.put(field, value);

    var failure =
        assertThrows(RegistrationValidationException.class, () -> validator.validate(attributes));

    assertEquals(List.of(new RegistrationViolation(field, code)), failure.violations());
  }
}
