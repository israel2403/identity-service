package mx.edu.orizaba.nahuatlcorpus.identity_service;

import static org.mockito.Mockito.mockStatic;

import org.junit.jupiter.api.Test;
import org.mockito.MockedStatic;
import org.springframework.boot.SpringApplication;

class IdentityServiceApplicationTest {

  @Test
  void forwardsCommandLineArgumentsToSpringBoot() {
    String[] args = {"--server.port=9090"};
    try (MockedStatic<SpringApplication> spring = mockStatic(SpringApplication.class)) {
      IdentityServiceApplication.main(args);
      spring.verify(() -> SpringApplication.run(IdentityServiceApplication.class, args));
    }
  }
}
