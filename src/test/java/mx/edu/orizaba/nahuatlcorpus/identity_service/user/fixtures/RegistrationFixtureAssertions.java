package mx.edu.orizaba.nahuatlcorpus.identity_service.user.fixtures;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import mx.edu.orizaba.nahuatlcorpus.identity_service.user.fixtures.RegistrationFixtures.FieldError;
import mx.edu.orizaba.nahuatlcorpus.identity_service.user.fixtures.RegistrationFixtures.Fixture;
import tools.jackson.databind.JsonNode;

/** RegistrationFixtureAssertions */
public final class RegistrationFixtureAssertions {
  private RegistrationFixtureAssertions() {}

  public static JsonNode response(
      Fixture fixture, int status, String body, Map<String, String> headers) {
    var expected = fixture.expected();
    assertEquals(expected.status(), status, "Fixture status must match");
    assertEquals(
        RegistrationFixtures.fixture("valid").request().contentType(), headers.get("Content-Type"));
    assertTrue(headers.getOrDefault("Cache-Control", "").contains("no-store"));
    assertTrue(headers.getOrDefault("Vary", "").contains("Accept"));
    List<String> forbidden =
        new ArrayList<>(RegistrationFixtures.settings().forbiddenResponseFragments());
    forbidden.add(RegistrationFixtures.rawPassword());
    JsonNode password = fixture.request().document().at("/data/attributes/password");
    if (password.isString()
        && password.stringValue().codePointCount(0, password.stringValue().length()) >= 15) {
      forbidden.add(password.stringValue());
    }
    for (String value : forbidden) {
      assertFalse(body.contains(value), "Response must omit credentials and internal details");
    }
    JsonNode document = RegistrationFixtures.mapper().readTree(body);
    if (status < 400) {
      assertFalse(document.has("errors"));
      assertTrue(Set.of("data", "jsonapi").containsAll(document.propertyNames()));
      JsonNode data = document.path("data");
      assertEquals(Set.of("type", "id", "attributes"), Set.copyOf(data.propertyNames()));
      assertEquals("users", data.path("type").stringValue());
      assertDoesNotThrow(() -> UUID.fromString(data.path("id").stringValue()));
      assertEquals(
          Set.of("fullname", "email"), Set.copyOf(data.path("attributes").propertyNames()));
      assertEquals(expected.fullname(), data.at("/attributes/fullname").stringValue());
      assertEquals(expected.email(), data.at("/attributes/email").stringValue());
      assertFalse(headers.containsKey("Location"));
    } else {
      assertFalse(document.has("data"));
      JsonNode errors = document.path("errors");
      assertTrue(errors.isArray() && !errors.isEmpty());
      List<FieldError> actual = new ArrayList<>();
      for (JsonNode error : errors) {
        assertEquals(Integer.toString(status), error.path("status").stringValue());
        assertTrue(error.path("title").isString());
        assertTrue(error.path("detail").isString());
        actual.add(
            new FieldError(
                error.path("code").stringValue(),
                error.at("/source/pointer").stringValue(null),
                error.at("/meta/field").stringValue(null)));
      }
      if (expected.errors() != null) {
        assertEquals(expected.errors().size(), actual.size());
        assertEquals(Set.copyOf(expected.errors()), Set.copyOf(actual));
      } else {
        assertEquals(
            List.of(new FieldError(expected.code(), expected.pointer(), expected.missingField())),
            actual);
      }
      for (FieldError error : actual) {
        if (error.pointer() != null) {
          assertFalse(
              RegistrationFixtures.mapper()
                  .readTree(fixture.body())
                  .at(error.pointer())
                  .isMissingNode(),
              "Error pointer must refer to an existing request node");
        }
      }
    }
    if (expected.allow() != null) {
      assertEquals(expected.allow(), headers.get("Allow"));
    }
    return document;
  }
}
