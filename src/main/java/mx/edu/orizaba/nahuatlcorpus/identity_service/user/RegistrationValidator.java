package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;
import org.springframework.stereotype.Component;

@Component
public class RegistrationValidator {
  private static final Pattern LOCAL =
      Pattern.compile("[a-z0-9!#$%&'*+/=?^_`{|}~-]+(?:\\.[a-z0-9!#$%&'*+/=?^_`{|}~-]+)*");
  private static final Pattern LABEL = Pattern.compile("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?");

  ValidatedRegistration validate(Map<String, ?> attributes) {
    var errors = new ArrayList<RegistrationViolation>();
    String name = stringField(attributes, "fullname", errors);
    String email = stringField(attributes, "email", errors);
    String password = stringField(attributes, "password", errors);
    if (name != null) {
      name = stripUnicodeSpace(name);
      if (!wellFormed(name)
          || points(name) < 1
          || points(name) > 100
          || name.codePoints().anyMatch(Character::isISOControl)) {
        errors.add(new RegistrationViolation("fullname", "INVALID_VALUE"));
      }
    }

    if (email != null) {
      email = stripAsciiSpace(email).toLowerCase(Locale.ROOT);
      if (!validEmail(email)) {
        errors.add(new RegistrationViolation("email", "INVALID_VALUE"));
      }
    }
    if (password != null) {
      if (!wellFormed(password)
          || points(password) < 15
          || points(password) > 128
          || password.codePoints().allMatch(RegistrationValidator::unicodeSpace)) {
        errors.add(new RegistrationViolation("password", "INVALID_VALUE"));
      }
    }
    if (!errors.isEmpty()) {
      throw new RegistrationValidationException(errors);
    }
    return new ValidatedRegistration(name, email, password);
  }

  private static boolean validEmail(String email) {
    if (email.length() > 254 || email.chars().anyMatch(c -> c > 127)) {
      return false;
    }
    int at = email.indexOf('@');
    if (at < 1 || at > 64 || at != email.lastIndexOf('@')) {
      return false;
    }
    String domain = email.substring(at + 1);
    if (!LOCAL.matcher(email.substring(0, at)).matches() || domain.length() > 253) {
      return false;
    }
    String[] labels = domain.split("\\.", -1);
    if (labels.length < 2) {
      return false;
    }
    for (String label : labels) {
      if (!LABEL.matcher(label).matches()) {
        return false;
      }
    }
    return true;
  }

  public static String stringField(
      Map<String, ?> attributes, String field, List<RegistrationViolation> errors) {
    if (!attributes.containsKey(field)) {
      errors.add(new RegistrationViolation(field, "REQUIRED_FIELD"));
      return null;
    }

    if (!(attributes.get(field) instanceof String value)) {
      errors.add(new RegistrationViolation(field, "INVALID_TYPE"));
      return null;
    }
    return value;
  }

  private static int points(String value) {
    return value.codePointCount(0, value.length());
  }

  public static boolean unicodeSpace(int cp) {
    return Character.isWhitespace(cp) || Character.isSpaceChar(cp);
  }

  private static boolean asciiSpace(int cp) {
    return cp == 32 || (cp >= 9 && cp <= 13);
  }

  private static String stripUnicodeSpace(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && unicodeSpace(value.codePointAt(start))) {
      start += Character.charCount(value.codePointAt(start));
    }
    while (start < end && unicodeSpace(value.codePointBefore(end))) {
      end -= Character.charCount(value.codePointBefore(end));
    }
    return value.substring(start, end);
  }

  private static String stripAsciiSpace(String value) {
    int start = 0;
    int end = value.length();
    while (start < end && asciiSpace(value.charAt(start))) {
      start++;
    }
    while (start < end && asciiSpace(value.charAt(end - 1))) {
      end--;
    }
    return value.substring(start, end);
  }

  public static boolean wellFormed(String value) {
    for (int i = 0; i < value.length(); i++) {
      char current = value.charAt(i);
      if (Character.isHighSurrogate(current)) {
        if (i + 1 >= value.length() || !Character.isLowSurrogate(value.charAt(i + 1))) {
          return false;
        }
        i++;
      } else if (Character.isLowSurrogate(current)) {
        return false;
      }
    }
    return true;
  }
}
