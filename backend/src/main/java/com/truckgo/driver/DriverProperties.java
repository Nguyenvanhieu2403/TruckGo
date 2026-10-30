package com.truckgo.driver;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.List;

/** Business configuration for driver licences (app.driver.*). */
@ConfigurationProperties("app.driver")
public record DriverProperties(String licenseNumberPattern, List<String> allowedLicenseClasses) { }
