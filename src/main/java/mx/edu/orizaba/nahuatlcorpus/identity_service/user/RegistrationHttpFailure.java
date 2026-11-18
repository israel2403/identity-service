package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

public class RegistrationHttpFailure extends RuntimeException {
  final int status;
  final String code;
  final String pointer;

  RegistrationHttpFailure(int status, String code, String pointer) {
    super("Registration request rejected");
    this.status = status;
    this.code = code;
    this.pointer = pointer;
  }
}
