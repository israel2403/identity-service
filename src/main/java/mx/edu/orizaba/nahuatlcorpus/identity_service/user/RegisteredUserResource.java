package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

import com.toedter.spring.hateoas.jsonapi.JsonApiId;
import com.toedter.spring.hateoas.jsonapi.JsonApiTypeForClass;

@JsonApiTypeForClass("users")
public record RegisteredUserResource(@JsonApiId String id, String fullname, String email) {}
