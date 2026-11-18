package mx.edu.orizaba.nahuatlcorpus.identity_service.user.integration;

import io.cucumber.spring.CucumberContextConfiguration;
import mx.edu.orizaba.nahuatlcorpus.identity_service.user.RegistrationService;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** RegistrationCucumberConfiguration */
@CucumberContextConfiguration
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RegistrationApiTestConfiguration.class)
public class RegistrationCucumberConfiguration {

  // Normal Scenarios call the real service. Only tagged failure scenarios stub it.
  @MockitoSpyBean RegistrationService service;
}
