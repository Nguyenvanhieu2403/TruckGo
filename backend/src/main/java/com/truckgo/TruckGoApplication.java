package com.truckgo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;

@SpringBootApplication
@ConfigurationPropertiesScan
public class TruckGoApplication {
    public static void main(String[] args) { SpringApplication.run(TruckGoApplication.class, args); }
}
