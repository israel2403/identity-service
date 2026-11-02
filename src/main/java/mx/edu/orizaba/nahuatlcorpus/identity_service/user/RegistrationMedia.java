package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

import java.util.Comparator;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;
import org.springframework.http.MediaType;

public class RegistrationMedia {
  static final String JSON_API = "application/vnd.api+json";
  private static final MediaType REPRESENTATION = MediaType.parseMediaType(JSON_API);
  private static final Predicate<MediaType> EXACT =
      type ->
          REPRESENTATION.getType().equalsIgnoreCase(type.getType())
              && REPRESENTATION.getSubtype().equalsIgnoreCase(type.getSubtype());

  private static final Predicate<MediaType> NO_EXTENSION = type -> type.getParameter("ext") == null;
  private static final Predicate<MediaType> CONTENT_SUPPORTED =
      EXACT.and(allowedParameters(Set.of("ext", "profile"))).and(NO_EXTENSION);
  private static final Predicate<MediaType> ACCEPT_SUPPORTED =
      allowedParameters(Set.of("ext", "profile", "q"))
          .and(type -> !EXACT.test(type) || NO_EXTENSION.test(type));
  private static final Comparator<Match> PREFERENCE =
      Comparator.comparingInt(Match::specificity).thenComparingDouble(Match::quality);

  private RegistrationMedia() {}

  static void check(String contentType, String accept) {
    checkContentType(contentType);
    checkAccept(accept);
  }

  public static void checkAccept(String value) {
    if (value == null || value.isBlank()) {
      return;
    }
    var ranges = parseAccept(value);
    var supported = ranges.stream().filter(ACCEPT_SUPPORTED).toList();
    // Unsupported exact alternatives cannot be rescued by a wildcard alone.
    if (ranges.stream().anyMatch(EXACT) && supported.stream().noneMatch(EXACT)) {
      throw new RegistrationMediaException(RegistrationMediaException.Header.ACCEPT);
    }

    var preferred =
        supported.stream()
            .filter(range -> range.includes(REPRESENTATION))
            .map(RegistrationMedia::match)
            .max(PREFERENCE);
    if (preferred.isEmpty() || preferred.get().quality <= 0) {
      throw new RegistrationMediaException(RegistrationMediaException.Header.ACCEPT);
    }
  }

  public static List<MediaType> parseAccept(String value) {
    try {
      return MediaType.parseMediaTypes(value);
    } catch (IllegalArgumentException failure) {
      throw new RegistrationMediaException(RegistrationMediaException.Header.ACCEPT);
    }
  }

  public static void checkContentType(String value) {
    MediaType input;
    try {
      input = MediaType.parseMediaType(value == null ? "" : value);
    } catch (IllegalArgumentException failure) {
      throw new RegistrationMediaException(RegistrationMediaException.Header.CONTENT_TYPE);
    }
    if (!CONTENT_SUPPORTED.test(input)) {
      throw new RegistrationMediaException(RegistrationMediaException.Header.CONTENT_TYPE);
    }
  }

  private static Match match(MediaType range) {
    try {
      return new Match(specificity(range), range.getQualityValue());
    } catch (IllegalArgumentException failure) {
      throw new RegistrationMediaException(RegistrationMediaException.Header.ACCEPT);
    }
  }

  private static int specificity(MediaType range) {
    if (EXACT.test(range)) {
      return 3;
    }
    if (range.isWildcardType()) {
      return 0;
    }
    // application/*+json is more specific than application/*
    return range.getSubtype().equals("*") ? 1 : 2;
  }

  private static Predicate<MediaType> allowedParameters(Set<String> allowed) {
    return type -> allowed.containsAll(type.getParameters().keySet());
  }

  private record Match(int specificity, double quality) {}
}
