package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

import java.util.List;

public class RegistrationValidationException extends RuntimeException {
  private final List<RegistrationViolation> violations;

  public RegistrationValidationException(List<RegistrationViolation> violations) {
    super("Registration attributes are invalid");
    this.violations = List.copyOf(violations);
  }

  public List<RegistrationViolation> violations() {
    return violations;
  }
}
