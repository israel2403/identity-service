package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.Arrays;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** CredentialExposureTest */
public class CredentialExposureTest {

  @Test
  void createdUserContainsOnlyPublicRegistrationFields() {
    var fields =
        Arrays.stream(CreatedUser.class.getRecordComponents())
            .map(component -> component.getName())
            .collect(Collectors.toSet());

    assertEquals(Set.of("id", "fullname", "email"), fields);
  }

  @Test
  void validatedInputStringRepresentationonOmitsPasswords() {
    String raw = "Synthetic redaction password 41!";
    var input = new ValidatedRegistration("Name", "person@example.org", raw);
    assertFalse(input.toString().contains(raw), "Validated input must redact credentails");
  }

  @Test
  void entityStringRepresentationOmitsEncodedCredentails() {
    String encoded = "synthetic-encoded-credentail-fixture";
    var entity = new UserAccount("Name", "person@example.org", encoded);

    assertFalse(entity.toString().contains(encoded), "Entity text must omit credentails");
  }
}
