package mx.edu.orizaba.nahuatlcorpus.identity_service.user.integration;

import org.junit.platform.suite.api.ConfigurationParameter;
import org.junit.platform.suite.api.IncludeEngines;
import org.junit.platform.suite.api.SelectPackages;
import org.junit.platform.suite.api.Suite;

/** RegistrationApiIT */
@Suite
@IncludeEngines("cucumber")
@SelectPackages("features.registration")
@ConfigurationParameter(
    key = "cucumber.glue",
    value = "mx.edu.orizaba.nahuatlcorpus.identity_service.user.integration")
@ConfigurationParameter(key = "cucumber.execution.parallel.enabled", value = "false")
public class RegistrationApiIT {}
