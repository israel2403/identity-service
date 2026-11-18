package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

import jakarta.servlet.http.HttpServletRequest;
import java.io.IOException;
import java.util.Collections;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class RegistrationController {
  private final RegistrationService registration;
  private final RegistrationJsonApi jsonApi;

  // Negotiate before reading/parsing; let advice translate failures.
  @RequestMapping("/api/v1/users")
  public ResponseEntity<String> register(HttpServletRequest request) throws IOException {
    if (!"POST".equals(request.getMethod())) {
      throw new RegistrationHttpFailure(405, "METHOD_NOT_ALLOWED", null);
    }
    RegistrationMedia.check(
        request.getContentType(), String.join(",", Collections.list(request.getHeaders("Accept"))));
    byte[] body = request.getInputStream().readNBytes(65537);
    if (body.length > 65536) {
      throw new RegistrationHttpFailure(413, "REQUEST_TOO_LARGE", null);
    }
    var created = registration.register(RegistrationDocument.attributes(body));
    return ResponseEntity.status(201)
        .header("Content-Type", RegistrationMedia.JSON_API)
        .header("Cache-Control", "no-store")
        .header("Vary", "Accept")
        .body(jsonApi.created(created));
  }
}
