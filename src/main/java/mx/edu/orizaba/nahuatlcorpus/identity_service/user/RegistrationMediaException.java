package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

public class RegistrationMediaException extends RuntimeException {
  /** Header */
  public enum Header {
    CONTENT_TYPE,
    ACCEPT
  }

  private final Header header;

  RegistrationMediaException(Header header) {
    super("Registration media rejected");
    this.header = header;
  }

  Header header() {
    return header;
  }
}
