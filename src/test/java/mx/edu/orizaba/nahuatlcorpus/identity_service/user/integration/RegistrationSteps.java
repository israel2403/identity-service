package mx.edu.orizaba.nahuatlcorpus.identity_service.user.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

import io.cucumber.java.After;
import io.cucumber.java.Before;
import io.cucumber.java.en.Given;
import io.cucumber.java.en.Then;
import io.cucumber.java.en.When;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import mx.edu.orizaba.nahuatlcorpus.identity_service.user.RegistrationService;
import mx.edu.orizaba.nahuatlcorpus.identity_service.user.UserAccountRepository;
import mx.edu.orizaba.nahuatlcorpus.identity_service.user.fixtures.RegistrationFixtureAssertions;
import mx.edu.orizaba.nahuatlcorpus.identity_service.user.fixtures.RegistrationFixtures;
import mx.edu.orizaba.nahuatlcorpus.identity_service.user.fixtures.RegistrationFixtures.Fixture;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.AopTestUtils;

/** RegistrationSteps */
public class RegistrationSteps {
  @Autowired UserAccountRepository repository;
  @Autowired RegistrationService registration;
  @Autowired PasswordEncoder encoder;
  @Autowired JdbcTemplate jdbc;
  @LocalServerPort int port;
  private HttpClient client;
  private Fixture fixture;
  private List<Map<String, Object>> responses;
  private Map<String, Object> response;

  private RegistrationService serviceSpy() {
    return AopTestUtils.getUltimateTargetObject(registration);
  }

  @Before
  public void startScenario() {
    reset(serviceSpy());
    repository.deleteAllInBatch();
    client =
        HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();
  }

  @After
  public void stpScenario() {
    try {
      if (client != null) {
        client.close();
      }
    } finally {
      reset(serviceSpy());
    }
  }

  @Given("a clean registration database and an anonymous HTTP client")
  public void cleanDatabaseAndClient() {
    assertEquals(0L, repository.count());
    assertTrue(client.cookieHandler().isEmpty());
    assertTrue(client.authenticator().isEmpty());
  }

  @Given("registration fixture {string}")
  public void loadFixture(String id) {
    fixture = RegistrationFixtures.fixture(id);
  }

  @Given("the registration service fails unexpectedly")
  public void failUnepectedly() {
    doThrow(new IllegalStateException(RegistrationFixtures.settings().failureMessage()))
        .when(serviceSpy())
        .register(any());
  }

  private Map<String, Object> specification(Fixture input) {
    return Map.of(
        "method",
        input.request().method(),
        "body",
        input.body(),
        "headers",
        Map.of("Content-Type", input.request().contentType(), "Accept", input.request().accept()));
  }

  private void send(List<Map<String, Object>> specs) {
    var pending =
        specs.stream()
            .map(
                spec -> {
                  var request =
                      HttpRequest.newBuilder(
                              URI.create("http://localhost:" + port + "/api/v1/users"))
                          .timeout(Duration.ofSeconds(15));

                  @SuppressWarnings("unchecked")
                  Map<String, String> headers = (Map<String, String>) spec.get("headers");
                  headers.forEach(request::header);
                  String method = (String) spec.get("method");
                  var body =
                      "POST".equals(method)
                          ? HttpRequest.BodyPublishers.ofString(
                              (String) spec.get("body"), StandardCharsets.UTF_8)
                          : HttpRequest.BodyPublishers.noBody();
                  return client.sendAsync(
                      request.method(method, body).build(),
                      HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                })
            .toList();
    CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new)).join();
    responses =
        pending.stream()
            .map(
                future -> {
                  var received = future.join();
                  Map<String, Object> output = new HashMap<>();
                  output.put("status", received.statusCode());
                  output.put("body", received.body());
                  output.put("redirected", received.previousResponse().isPresent());
                  Map<String, String> names =
                      Map.of(
                          "type",
                          "Content-Type",
                          "cache",
                          "Cache-Control",
                          "vary",
                          "Vary",
                          "allow",
                          "Allow",
                          "location",
                          "Location");
                  names.forEach(
                      (key, header) ->
                          received
                              .headers()
                              .firstValue(header)
                              .ifPresent(value -> output.put(key, value)));
                  assertTrue(
                      received.headers().allValues("Set-cookie").isEmpty(),
                      "Registration must not create a session cookie");
                  return output;
                })
            .toList();
    response = responses.get(0);
  }

  @When("the client submits the registration fixture")
  public void submit() {
    send(List.of(specification(fixture)));
  }

  private void assertResponse(Fixture input, Map<String, Object> output) {
    Map<String, String> headers = new HashMap<>();
    Map<String, String> names =
        Map.of(
            "type",
            "Content-Type",
            "cache",
            "Cache-Control",
            "vary",
            "Vary",
            "allow",
            "Allow",
            "location",
            "Location");
    names.forEach(
        (key, header) -> {
          if (output.get(key) != null) {
            headers.put(header, output.get(key).toString());
          }
        });
    assertEquals(false, output.get("redirected"));
    RegistrationFixtureAssertions.response(
        input, ((Number) output.get("status")).intValue(), (String) output.get("body"), headers);
  }

  @Then("the response and database match the fixture")
  public void responseMatches() {
    assertResponse(fixture, response);
    assertEquals((long) fixture.expected().accounts(), repository.count());
  }

  @Then("the committed password verifies against the fixture")
  public void storedPassword() {
    var document = RegistrationFixtures.mapper().readTree((String) response.get("body"));
    UUID id = UUID.fromString(document.at("/data/id").stringValue());
    String raw = fixture.request().document().at("/data/attributes/password").stringValue();
    String stored =
        jdbc.queryForObject(
            "SELECT password_hash FROM identity_service.user_accounts WHERE id = ?",
            String.class,
            id);
    assertNotNull(stored);
    assertTrue(stored.startsWith("{argon2id-v1}"), "Configured encoding must be stored");
    assertFalse(raw.equals(stored), "Plaintext must not be stored");
    assertTrue(encoder.matches(raw, stored), "Original fixture password must verify");
    for (String invalid : RegistrationFixtures.settings().invalidPasswords()) {
      assertFalse(
          encoder.matches(invalid, stored),
          "Changed or incorrect fixture password must be rejected");
    }
  }

  @Then("the client remains anonymous")
  public void anonymous() {
    var request =
        HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/private"))
            .timeout(Duration.ofSeconds(10))
            .header("Accept", "application/json")
            .header("X-Requested-With", "XMLHttpRequest")
            .GET()
            .build();
    var protectedResponse =
        client.sendAsync(request, HttpResponse.BodyHandlers.discarding()).join();
    assertEquals(401, protectedResponse.statusCode());
    assertTrue(protectedResponse.headers().allValues("Set-Cookie").isEmpty());
  }

  @When("the client submits two fixture email variants concurrently")
  public void concurrentSubmissions() {
    send(
        List.of(
            specification(RegistrationFixtures.fixture("valid")),
            specification(RegistrationFixtures.fixture("concurrent-second"))));
  }

  @Then("the concurrent responses match the fixtures")
  public void oneWinner() {
    var settings = RegistrationFixtures.settings();
    assertEquals(
        settings.concurrentStatuses(),
        responses.stream().map(item -> ((Number) item.get("status")).intValue()).sorted().toList());
    var success = RegistrationFixtures.fixture("valid");
    var duplicate = RegistrationFixtures.fixture("duplicate");
    for (var output : responses) {
      assertResponse(
          ((Number) output.get("status")).intValue() == success.expected().status()
              ? success
              : duplicate,
          output);
    }
    assertEquals((long) settings.concurrentAccounts(), repository.count());
  }
}
