package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.SQLException;
import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

/** EmailConstraintClassifierTest */
public class EmailConstraintClassifierTest {

  private DataIntegrityViolationException failure(String state, String constraint) {
    var sql = new SQLException("Synthetic database failure", state);
    var hibernate =
        new ConstraintViolationException("Synthetic constraint failure", sql, constraint);
    return new DataIntegrityViolationException("Synthetic persistance failure", hibernate);
  }

  @Test
  void recognizesMatchingStateAndConstraint() {
    assertTrue(EmailConstraintClassifier.isDuplicate(failure("23505", "uq_user_accounts_email")));
  }

  @Test
  void rejectsExceptionWithoutHibernateCause() {
    assertFalse(
        EmailConstraintClassifier.isDuplicate(
            new DataIntegrityViolationException("Synthetic persistance failure")));
  }

  @Test
  void rejectsCorrectStateWithWrongConstraint() {
    assertFalse(EmailConstraintClassifier.isDuplicate(failure("23505", "uq_another_constraint")));
  }

  @Test
  void rejectsWrongStateWithCorrectConstraint() {
    assertFalse(EmailConstraintClassifier.isDuplicate(failure("23514", "uq_user_accounts_email")));
  }
}
