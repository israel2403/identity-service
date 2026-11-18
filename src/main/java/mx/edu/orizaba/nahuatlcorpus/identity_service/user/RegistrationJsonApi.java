package mx.edu.orizaba.nahuatlcorpus.identity_service.user;

import com.toedter.spring.hateoas.jsonapi.JsonApiErrors;
import com.toedter.spring.hateoas.jsonapi.JsonApiMediaTypeConfiguration;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.hateoas.EntityModel;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
public class RegistrationJsonApi {
  // Scoped mapper: no accidental changes to unrelated endpoint serialization.
  private final JsonMapper mapper;

  public RegistrationJsonApi(AutowireCapableBeanFactory beanFactory) {
    // Configure the module AND its serializer instantiator through the library API.
    mapper =
        new JsonApiMediaTypeConfiguration(null, beanFactory)
            .configureJsonMapper(JsonMapper.builder())
            .build();
  }

  String created(CreatedUser user) {
    return mapper.writeValueAsString(
        EntityModel.of(
            new RegisteredUserResource(user.id().toString(), user.fullname(), user.email())));
  }

  String errors(JsonApiErrors errors) {
    return mapper.writeValueAsString(errors);
  }
}
