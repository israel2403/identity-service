package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

public class DuplicateEmailException extends RuntimeException {
  public DuplicateEmailException() {
    super("An account with this email already exists");
  }
}
