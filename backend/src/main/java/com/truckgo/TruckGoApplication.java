package com.truckgo;

import com.truckgo.admin.AdminBootstrapRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

import java.util.Arrays;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TruckGoApplication {
    public static void main(String[] args) {
        SpringApplication app = new SpringApplication(TruckGoApplication.class);
        boolean bootstrapAdmin = Arrays.asList(args).contains("--" + AdminBootstrapRunner.OPTION);
        if (bootstrapAdmin) app.setWebApplicationType(WebApplicationType.NONE);
        var context = app.run(args);
        if (bootstrapAdmin) System.exit(SpringApplication.exit(context));
    }
}
