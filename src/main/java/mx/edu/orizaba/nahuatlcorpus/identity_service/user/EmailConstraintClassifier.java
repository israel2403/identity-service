package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

import org.hibernate.exception.ConstraintViolationException;

final class EmailConstraintClassifier {
  private EmailConstraintClassifier() {}

  static boolean isDuplicate(Throwable failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof ConstraintViolationException violation
          && "23505".equals(violation.getSQLState())
          && "uq_user_accounts_email".equals(violation.getConstraintName())) {
        return true;
      }
    }
    return false;
  }
}
