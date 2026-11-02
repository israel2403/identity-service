package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

import com.toedter.spring.hateoas.jsonapi.JsonApiError;
import com.toedter.spring.hateoas.jsonapi.JsonApiErrors;
import java.io.IOException;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice(assignableTypes = RegistrationController.class)
@RequiredArgsConstructor
public class GlobalExceptionHandler {
  private final RegistrationJsonApi jsonApi;

  @ExceptionHandler(RegistrationMediaException.class)
  public ResponseEntity<String> media(RegistrationMediaException failure) {
    return switch (failure.header()) {
      case CONTENT_TYPE -> response(415, errors(error(415, "UNSUPPORTED_MEDIA_TYPE", null, null)));
      case ACCEPT -> response(406, errors(error(406, "NOT_ACCEPTABLE", null, null)));
    };
  }

  @ExceptionHandler(RegistrationHttpFailure.class)
  public ResponseEntity<String> requestFailure(RegistrationHttpFailure failure) {
    var result =
        response(
            failure.status, errors(error(failure.status, failure.code, failure.pointer, null)));
    if (failure.status == 405) {
      return ResponseEntity.status(405)
          .headers(result.getHeaders())
          .header("Allow", "POST")
          .body(result.getBody());
    }
    return result;
  }

  @ExceptionHandler(RegistrationValidationException.class)
  public ResponseEntity<String> validation(RegistrationValidationException failure) {
    JsonApiErrors items = JsonApiErrors.create();
    for (var violation : failure.violations()) {
      boolean missing = "REQUIRED_FIELD".equals(violation.code());
      items.withError(
          error(
              422,
              violation.code(),
              missing ? "/data/attributes" : "/data/attributes/" + violation.field(),
              missing ? violation.field() : null));
    }
    return response(422, items);
  }

  @ExceptionHandler(DuplicateEmailException.class)
  public ResponseEntity<String> duplicate(DuplicateEmailException failure) {
    return response(
        409, errors(error(409, "EMAIL_ALREADY_REGISTERED", "/data/attributes/email", null)));
  }

  @ExceptionHandler({IOException.class, RuntimeException.class})
  public ResponseEntity<String> unexpected(Exception failure) {
    // Never log the exception or serialize its message, cause or stack trace.
    // An optional metric may use only a fixed event label.
    return response(500, errors(error(500, "INTERNAL_ERROR", null, null)));
  }

  private ResponseEntity<String> response(int status, JsonApiErrors errors) {
    return response(status, jsonApi.errors(errors));
  }

  private static ResponseEntity<String> response(int status, String body) {
    return ResponseEntity.status(status)
        .header("Content-Type", RegistrationMedia.JSON_API)
        .header("Cache-Control", "no-store")
        .header("Vary", "Accept")
        .body(body);
  }

  public static JsonApiErrors errors(JsonApiError error) {
    return JsonApiErrors.create().withError(error);
  }

  public static JsonApiError error(int status, String code, String pointer, String field) {
    String detail =
        switch (code) {
          case "REQUIRED_FIELD" -> field + " is required.";
          case "INVALID_TYPE" -> "Attribute must be a string.";
          case "INVALID_VALUE" -> "Attribute violates the registration policy.";
          case "EMAIL_ALREADY_REGISTERED" -> "An account with this email already exists.";
          case "RESOURCE_TYPE_CONFLICT" -> "Resource type must be users.";
          case "CLIENT_ID_NOT_SUPPORTED" -> "Client-generated IDs are not supported.";
          case "MALFORMED_JSON" -> "Request must contain one valid JSON document.";
          case "INVALID_DOCUMENT" -> "Invalid registration document.";
          case "UNSUPPORTED_FIELD" -> "Request contains unsupported writable input.";
          case "UNSUPPORTED_MEDIA_TYPE" -> "Use application/vnd.api+json for the request.";
          case "NOT_ACCEPTABLE" -> "No acceptable JSON:API representation is available.";
          case "REQUEST_TOO_LARGE" -> "Registration document exceeds 65536 bytes.";
          case "METHOD_NOT_ALLOWED" -> "Use POST for registration.";
          default -> "Registration could not be completed.";
        };
    String title =
        status == 422
            ? "Invalid registration attributes"
            : status == 409
                ? "Registration conflict"
                : status >= 500 ? "Internal server error" : "Registration request rejected";
    JsonApiError item =
        JsonApiError.create()
            .withStatus(Integer.toString(status))
            .withCode(code)
            .withTitle(title)
            .withDetail(detail);
    if (pointer != null) {
      item = item.withSourcePointer(pointer);
    }
    if (field != null) {
      item = item.withMeta(Map.of("field", field));
    }
    return item;
  }
}
