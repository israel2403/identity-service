package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

import static java.util.Collections.list;
import static java.util.List.copyOf;
import static mx.edu.orizaba.nahuatlcorpus.identity_service.user.fixtures.RegistrationFixtureAssertions.response;
import static mx.edu.orizaba.nahuatlcorpus.identity_service.user.fixtures.RegistrationFixtures.settings;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.http.ResponseEntity.status;
import static org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import mx.edu.orizaba.nahuatlcorpus.identity_service.user.fixtures.RegistrationFixtures;
import mx.edu.orizaba.nahuatlcorpus.identity_service.user.fixtures.RegistrationFixtures.Fixture;
import mx.edu.orizaba.nahuatlcorpus.identity_service.user.fixtures.RegistrationFixtures.Settings;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

/** RegistrationControllerTest */
public class RegistrationControllerTest {
  private final RegistrationService service = mock(RegistrationService.class);
  private final RegistrationController controller =
      new RegistrationController(
          service, new RegistrationJsonApi(new DefaultListableBeanFactory()));
  private final MockMvc mvc =
      standaloneSetup(controller)
          .setControllerAdvice(
              new GlobalExceptionHandler(new RegistrationJsonApi(new DefaultListableBeanFactory())))
          .build();
  private final Settings settings = settings();

  ResponseEntity<String> dispatch(MockHttpServletRequest input) {
    try {
      var result =
          mvc.perform(
                  MockMvcRequestBuilders.request(
                          HttpMethod.valueOf(input.getMethod()), input.getRequestURI())
                      .with(
                          request -> {
                            request.setContent(input.getContentAsByteArray());
                            list(input.getHeaderNames())
                                .forEach(
                                    name ->
                                        list(input.getHeaders(name))
                                            .forEach(value -> request.addHeader(name, value)));
                            return request;
                          }))
              .andReturn()
              .getResponse();
      var headers = new HttpHeaders();
      result.getHeaderNames().forEach(name -> headers.put(name, copyOf(result.getHeaders(name))));
      return status(result.getStatus()).headers(headers).body(result.getContentAsString());
    } catch (Exception failure) {
      throw new AssertionError("MVC dispatch must complete", failure);
    }
  }

  private ResponseEntity<String> dispatch(Fixture fixture) {
    var request = new MockHttpServletRequest(fixture.request().method(), "/api/v1/users");
    request.setContentType(fixture.request().contentType());
    request.addHeader("Accept", fixture.request().accept());
    request.setContent(fixture.bytes());
    return dispatch(request);
  }

  private void assertFixture(Fixture fixture, ResponseEntity<String> response) {
    Map<String, String> headers = new HashMap<>();
    response.getHeaders().forEach((name, values) -> headers.put(name, String.join(",", values)));
    response(fixture, response.getStatusCode().value(), response.getBody(), headers);
  }

  @Test
  void returnsOnlyPublicFieldsAndDoesNotCoerceInput() {
    var fixture = RegistrationFixtures.fixture("valid");
    String raw = RegistrationFixtures.rawPassword();
    when(service.register(anyMap()))
        .thenAnswer(
            invocation -> {
              Map<String, Object> attributes = invocation.getArgument(0);
              assertTrue(raw.equals(attributes.get("password")), "Password must remain unchaged");
              return new CreatedUser(
                  UUID.fromString(settings.unitId()),
                  fixture.expected().fullname(),
                  fixture.expected().email());
            });
    assertFixture(fixture, dispatch(fixture));
    verify(service).register(anyMap());
  }

  @Test
  void perservesMissingNullAndWrongTypeForTheValidator() {
    var fixture = RegistrationFixtures.fixture("mixed-types");
    var attributes = RegistrationDocument.attributes(fixture.bytes());
    assertFalse(attributes.containsKey("fullname"));
    assertTrue(attributes.containsKey("email"));
    assertNull(attributes.get("email"));
    assertNull(attributes.get("password"));
    for (var wrongType : RegistrationFixtures.group("type")) {
      assertThrows(
          RegistrationValidationException.class,
          () ->
              new RegistrationValidator()
                  .validate(RegistrationDocument.attributes(wrongType.bytes())));
    }
  }

  @Test
  void rejectsEnvelopesBeforeCallingService() {
    for (var fixture : RegistrationFixtures.group("envelope")) {
      assertFixture(fixture, dispatch(fixture));
    }
    verifyNoInteractions(service);
  }

  @Test
  void mapsSafeValidationDuplicateAndUnexpectedFailures() {
    doThrow(new RegistrationValidationException(settings().violations()))
        .when(service)
        .register(anyMap());
    var validation = RegistrationFixtures.fixture("validation-stub");
    assertFixture(validation, dispatch(validation));
    doThrow(new DuplicateEmailException()).when(service).register(anyMap());
    var duplicate = RegistrationFixtures.fixture("duplicate");
    assertFixture(duplicate, dispatch(duplicate));
    doThrow(new IllegalStateException(settings.failureMessage())).when(service).register(anyMap());
    var failure = RegistrationFixtures.fixture("server-failure");
    assertFixture(failure, dispatch(failure));
  }

  @Test
  void negotiatesBeforeParsingAndAllowMetadata() {
    var wrongMedia = RegistrationFixtures.fixture("wrong-content");
    assertFixture(wrongMedia, dispatch(wrongMedia));
    verifyNoInteractions(service);
    var attributes =
        RegistrationDocument.attributes(RegistrationFixtures.fixture("metadata-empty").bytes());
    assertTrue(attributes.isEmpty());
  }

  @Test
  void adviceHandlesMethodBodyLimitAndUnacceptableAccept() {
    for (String id : List.of("method", "oversized", "wrong-accept")) {
      var fixture = RegistrationFixtures.fixture(id);
      assertFixture(fixture, dispatch(fixture));
    }
    verifyNoInteractions(service);
  }

  @Test
  void checksMediaParametersWildcardsAndQualityValues() {
    for (var fixture : RegistrationFixtures.group("media-accepted")) {
      assertDoesNotThrow(
          () ->
              RegistrationMedia.check(fixture.request().contentType(), fixture.request().accept()));
    }

    for (var fixture : RegistrationFixtures.group("media-rejected")) {
      var failure =
          assertThrows(
              RegistrationMediaException.class,
              () ->
                  RegistrationMedia.check(
                      fixture.request().contentType(), fixture.request().accept()));
      var expectedHeader =
          fixture.expected().status() == 415
              ? RegistrationMediaException.Header.CONTENT_TYPE
              : RegistrationMediaException.Header.ACCEPT;
      assertEquals(expectedHeader, failure.header());
    }
  }
}
