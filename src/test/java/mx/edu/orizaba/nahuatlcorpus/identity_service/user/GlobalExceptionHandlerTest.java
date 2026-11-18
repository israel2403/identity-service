package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import mx.edu.orizaba.nahuatlcorpus.identity_service.user.fixtures.RegistrationFixtureAssertions;
import mx.edu.orizaba.nahuatlcorpus.identity_service.user.fixtures.RegistrationFixtures;
import mx.edu.orizaba.nahuatlcorpus.identity_service.user.fixtures.RegistrationFixtures.Fixture;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.ResponseEntity;

/** GlobalExceptionHandlerTest */
public class GlobalExceptionHandlerTest {
  private final GlobalExceptionHandler advice =
      new GlobalExceptionHandler(new RegistrationJsonApi(new DefaultListableBeanFactory()));

  private void assertFixture(Fixture fixture, ResponseEntity<String> response) {
    var headers = new HashMap<String, String>();
    response.getHeaders().forEach((name, values) -> headers.put(name, String.join(",", values)));
    RegistrationFixtureAssertions.response(
        fixture, response.getStatusCode().value(), response.getBody(), headers);
  }

  @Test
  void mapsGuardFailuresWithPointersAndAllowHeader() {
    for (String id : List.of("bulk-data", "method")) {
      var fixture = RegistrationFixtures.fixture(id);
      var expected = fixture.expected();
      assertFixture(
          fixture,
          advice.requestFailure(
              new RegistrationHttpFailure(expected.status(), expected.code(), expected.pointer())));
    }
  }

  @Test
  void mapsMediaFailuresToTheirHeaderSpecificStatusAndCode() {
    assertFixture(
        RegistrationFixtures.fixture("wrong-content"),
        advice.media(
            new RegistrationMediaException(RegistrationMediaException.Header.CONTENT_TYPE)));
    assertFixture(
        RegistrationFixtures.fixture("wrong-accept"),
        advice.media(new RegistrationMediaException(RegistrationMediaException.Header.ACCEPT)));
  }

  @Test
  void aggregatesValidationAndPreservesDuplicatePointer() {
    assertFixture(
        RegistrationFixtures.fixture("validation-stub"),
        advice.validation(
            new RegistrationValidationException(RegistrationFixtures.settings().violations())));
    assertFixture(
        RegistrationFixtures.fixture("duplicate"), advice.duplicate(new DuplicateEmailException()));
  }

  @Test
  void sanitizesIoAndRuntimeFailuresIncludingTheirCauses() {
    var settings = RegistrationFixtures.settings();
    var cause = new IllegalArgumentException(settings.causeMessage());
    for (Exception failure :
        List.of(
            new IOException(settings.failureMessage(), cause),
            new IllegalStateException(settings.failureMessage(), cause),
            new IllegalArgumentException(settings.failureMessage(), cause))) {
      assertFixture(RegistrationFixtures.fixture("server-failure"), advice.unexpected(failure));
    }
  }
}
