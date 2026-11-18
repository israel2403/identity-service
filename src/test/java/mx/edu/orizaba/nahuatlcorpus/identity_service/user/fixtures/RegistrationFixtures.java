package mx.edu.orizaba.nahuatlcorpus.identity_service.user.fixtures;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import mx.edu.orizaba.nahuatlcorpus.identity_service.user.RegistrationViolation;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** RegistrationFixtures */
public final class RegistrationFixtures {
  private static final ObjectMapper MAPPER =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private RegistrationFixtures() {}

  public record Requests(Map<String, RequestData> cases) {}

  public record Expectations(Settings settings, Map<String, Expected> cases) {}

  public record BodyPattern(String unit, int count) {}

  public record RequestData(
      String group,
      String method,
      JsonNode document,
      String rawBody,
      BodyPattern bodyPattern,
      String contentType,
      String accept) {
    @Override
    public String toString() {
      return "RequestData[redacted]";
    }
  }

  public record FieldError(String code, String pointer, String missingField) {}

  public record Expected(
      int status,
      int accounts,
      String code,
      String pointer,
      String missingField,
      String allow,
      List<FieldError> errors,
      String fullname,
      String email) {}

  public record Settings(
      String unitId,
      String failureMessage,
      String causeMessage,
      List<String> forbiddenResponseFragments,
      List<String> invalidPasswords,
      List<RegistrationViolation> violations,
      List<Integer> concurrentStatuses,
      int concurrentAccounts) {
    @Override
    public String toString() {
      return "Settings[redacted]";
    }
  }

  public record Fixture(String id, RequestData request, Expected expected) {
    public String body() {
      if (request.rawBody() != null) {
        return request.rawBody();
      }
      if (request.bodyPattern() != null) {
        return request.bodyPattern().unit().repeat(request.bodyPattern().count());
      }
      return MAPPER.writeValueAsString(request.document());
    }

    public byte[] bytes() {
      return body().getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    @Override
    public final String toString() {
      return "Fixture[" + id + "]";
    }
  }

  public static ObjectMapper mapper() {
    return MAPPER;
  }

  public static Settings settings() {
    return read("expectations.json", Expectations.class).settings();
  }

  public static Fixture fixture(String id) {
    Requests requests = read("requests.json", Requests.class);
    Expectations expectations = read("expectations.json", Expectations.class);
    return resolve(id, requests, expectations);
  }

  public static List<Fixture> group(String group) {
    Requests requests = read("requests.json", Requests.class);
    Expectations expectations = read("expectations.json", Expectations.class);
    return requests.cases().entrySet().stream()
        .filter(entry -> group.equals(entry.getValue().group()))
        .map(entry -> resolve(entry.getKey(), requests, expectations))
        .toList();
  }

  public static String rawPassword() {
    return fixture("valid").request().document().at("/data/attributes/password").stringValue();
  }

  private static Fixture resolve(String id, Requests requests, Expectations expectations) {
    RequestData input = requests.cases().get(id);
    Expected expected = expectations.cases().get(id);
    if (input == null || expected == null) {
      throw new AssertionError("Unknown or incomplete registration fixture: " + id);
    }
    RequestData defaults = requests.cases().get("valid");
    return new Fixture(
        id,
        new RequestData(
            input.group(),
            input.method(),
            input.document() == null ? defaults.document().deepCopy() : input.document().deepCopy(),
            input.rawBody(),
            input.bodyPattern(),
            input.contentType() == null ? defaults.contentType() : input.contentType(),
            input.accept() == null ? defaults.accept() : input.accept()),
        expected);
  }

  private static <T> T read(String file, Class<T> type) {
    String resource = "/fixtures/registration/" + file;
    try (InputStream input = RegistrationFixtures.class.getResourceAsStream(resource)) {
      if (input == null) {
        throw new AssertionError("Missing registration fixture resource: " + resource);
      }
      return MAPPER.readValue(input, type);
    } catch (IOException | RuntimeException failure) {
      // Do not echo parser messages, fixture payloads or credential values.
      throw new AssertionError("Cannot deserialize registration fixture resource: " + resource);
    }
  }
}
