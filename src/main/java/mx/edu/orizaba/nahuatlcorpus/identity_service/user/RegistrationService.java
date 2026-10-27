package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class RegistrationService {
  private final RegistrationValidator validator;
  private final PasswordEncoder encoder;
  private final UserAccountWriter writer;

  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public CreatedUser register(Map<String, ?> attributes) {
    var input = validator.validate(attributes);
    String encoded = encoder.encode(input.pasword());
    try {
      return writer.create(input.fullName(), input.email(), encoded);
    } catch (DataIntegrityViolationException failure) {
      if (EmailConstraintClassifier.isDuplicate(failure)) {
        throw new DuplicateEmailException();
      }
      throw failure;
    }
  }
}
