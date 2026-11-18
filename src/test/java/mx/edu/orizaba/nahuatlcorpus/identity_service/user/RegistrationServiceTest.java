package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;

/** RegistrationServiceTest */
public class RegistrationServiceTest {

  private static final String RAW = "  Synthetic unit café 🔐 password 42!  ";
  private static final String ENCODED = "synthetic-encoded-value";

  private final PasswordEncoder encoder = mock(PasswordEncoder.class);
  private final UserAccountWriter writer = mock(UserAccountWriter.class);
  private final RegistrationService registration =
      new RegistrationService(new RegistrationValidator(), encoder, writer);

  private Map<String, Object> request() {
    return new HashMap<>(
        Map.of(
            "fullname",
            "\u00a0María Ācatl\u00a0",
            "email",
            " Person@Example.ORG ",
            "password",
            RAW));
  }

  @Test
  void invalidInputNeverCallsEncoderOrWriter() {
    var attributes = request();
    attributes.put("email", "invalid");

    assertThrows(RegistrationValidationException.class, () -> registration.register(attributes));

    verifyNoInteractions(encoder, writer);
  }

  @Test
  void hashesOriginalPasswordOnceAndPassesCanonicalFieldsToWriter() {
    var expected = new CreatedUser(UUID.randomUUID(), "María Ācatl", "person@example.org");

    when(encoder.encode(anyString()))
        .thenAnswer(
            invocation -> {
              String received = invocation.getArgument(0);
              assertTrue(RAW.equals(received), "Encoder must receive the original password");
              return ENCODED;
            });

    doAnswer(
            invocation -> {
              String name = invocation.getArgument(0);
              String email = invocation.getArgument(1);
              String credential = invocation.getArgument(2);

              assertTrue("María Ācatl".equals(name), "Writer must receive normalized fullname");
              assertTrue("person@example.org".equals(email), "Writer must receive canonical email");
              assertTrue(
                  ENCODED.equals(credential), "Writer must receive only encoded credentials");
              return expected;
            })
        .when(writer)
        .create(anyString(), anyString(), anyString());

    assertSame(expected, registration.register(request()));

    verify(encoder, times(1)).encode(anyString());
    verify(writer, times(1)).create(anyString(), anyString(), anyString());
    verifyNoMoreInteractions(encoder, writer);
  }

  @Test
  void encoderFailureNeverCallsWriter() {
    var expected = new IllegalStateException("Synthetic encoding failure");
    when(encoder.encode(anyString())).thenThrow(expected);

    var actual = assertThrows(IllegalStateException.class, () -> registration.register(request()));

    assertSame(expected, actual);
    verifyNoInteractions(writer);
  }

  @Test
  void unrelatedDataIntegrityFailureIsRethrownUnchaged() {
    var expected = new DataIntegrityViolationException("Synthetic integrity failure");
    when(encoder.encode(anyString())).thenReturn(ENCODED);
    when(writer.create(anyString(), anyString(), anyString())).thenThrow(expected);

    var actual =
        assertThrows(DataIntegrityViolationException.class, () -> registration.register(request()));
    assertSame(expected, actual);
  }

  @Test
  void unrelatedInfrastructureFailureIsRethrownUnchaged() {
    var expected = new IllegalStateException("Synthetic infrastrucutre failure");
    when(encoder.encode(anyString())).thenReturn(ENCODED);
    when(writer.create(anyString(), anyString(), anyString())).thenThrow(expected);

    var actual = assertThrows(IllegalStateException.class, () -> registration.register(request()));

    assertSame(expected, actual);
  }
}
