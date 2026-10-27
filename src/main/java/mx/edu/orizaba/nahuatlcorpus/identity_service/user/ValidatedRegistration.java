package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

final class ValidatedRegistration {
  private final String fullname;
  private final String email;
  private final String password;

  public ValidatedRegistration(String fullname, String email, String password) {
    this.fullname = fullname;
    this.email = email;
    this.password = password;
  }

  String fullName() {
    return fullname;
  }

  String email() {
    return email;
  }

  String pasword() {
    return password;
  }

  @Override
  public String toString() {
    return "ValidatedRegistration[redacted]";
  }
}
