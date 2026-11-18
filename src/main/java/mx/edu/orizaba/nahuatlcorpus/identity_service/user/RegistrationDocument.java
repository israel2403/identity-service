package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class RegistrationDocument {
  static final JsonMapper JSON =
      JsonMapper.builder()
          .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
          .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
          .build();

  private RegistrationDocument() {}

  static Map<String, Object> attributes(byte[] body) {
    JsonNode root;
    try {
      root = JSON.readTree(body);
    } catch (JacksonException failure) {
      throw new RegistrationHttpFailure(400, "MALFORMED_JSON", null);
    }
    require(root != null && root.isObject(), "");
    require(!root.has("errors"), "");
    objectIfPresent(root, "meta", "/meta");
    linksIfPresent(root, "/links");
    if (root.has("jsonapi")) {
      JsonNode api = root.get("jsonapi");
      require(api.isObject(), "/jsonapi");
      require(!api.has("version") || api.get("version").isString(), "/jsonapi");
      objectIfPresent(api, "meta", "/jsonapi/meta");
      for (String name : Set.of("ext", "profile")) {
        if (api.has(name)) {
          require(api.get(name).isArray(), "/jsonapi/" + name);
          for (JsonNode value : api.get(name)) {
            require(value.isString(), "/jsonapi/" + name);
          }
        }
      }
    }

    // Relationships are not writable, so no included resource can have full linkage.
    if (root.has("included")) {
      require(root.get("included").isArray() && root.get("included").isEmpty(), "/included");
    }
    require(root.has("data"), "");
    JsonNode data = root.get("data");
    require(data.isObject(), "/data");
    require(data.has("type"), "/data");
    require(data.get("type").isString(), "/data/type");
    if (!"users".equals(data.get("type").stringValue())) {
      throw new RegistrationHttpFailure(409, "RESOURCE_TYPE_CONFLICT", "/data/type");
    }

    if (data.has("id")) {
      throw new RegistrationHttpFailure(403, "CLIENT_ID_NOT_SUPPORTED", "/data/id");
    }
    require(!data.has("lid") || data.get("lid").isString(), "/data");
    objectIfPresent(data, "meta", "/data/meta");
    linksIfPresent(data, "/data/links");
    if (data.has("relationships")) {
      throw new RegistrationHttpFailure(400, "UNSUPPORTED_FIELD", "/data/relationships");
    }
    require(data.has("attributes"), "/data");
    JsonNode attributes = data.get("attributes");
    require(attributes.isObject(), "/data/attributes");
    Map<String, Object> result = new HashMap<>();
    for (String name : attributes.propertyNames()) {
      if (!Set.of("fullname", "email", "password").contains(name)) {
        // Use the existing parent, never an untrusted property name.
        throw new RegistrationHttpFailure(400, "UNSUPPORTED_FIELD", "/data/attributes");
      }
      JsonNode value = attributes.get(name);
      // Preserve missing vs null and String vs non-String; never coerce JSON types.
      result.put(name, value.isString() ? value.stringValue() : null);
    }
    return result;
  }

  public static void linksIfPresent(JsonNode owner, String pointer) {
    if (!owner.has("links")) {
      return;
    }
    JsonNode links = owner.get("links");
    require(links.isObject(), pointer);
    for (JsonNode link : links) {
      if (link.isNull() || link.isString()) {
        continue;
      }
      require(link.isObject() && link.has("href") && link.get("href").isString(), pointer);
      objectIfPresent(link, "meta", pointer);
      for (String member : Set.of("rel", "title", "type")) {
        require(!link.has(member) || link.get(member).isString(), pointer);
      }
      if (link.has("describedby")) {
        JsonNode described = link.get("describedby");
        require(
            described.isString()
                || (described.isObject()
                    && described.has("href")
                    && described.get("href").isString()),
            pointer);
      }
      if (link.has("hreflang")) {
        JsonNode language = link.get("hreflang");
        require(language.isString() || language.isArray(), pointer);
        if (language.isArray()) {
          for (JsonNode value : language) {
            require(value.isString(), pointer);
          }
        }
      }
    }
  }

  public static void objectIfPresent(JsonNode node, String name, String pointer) {
    require(!node.has(name) || node.get(name).isObject(), pointer);
  }

  public static void require(boolean condition, String pointer) {
    if (!condition) {
      throw new RegistrationHttpFailure(400, "INVALID_DOCUMENT", pointer);
    }
  }
}
