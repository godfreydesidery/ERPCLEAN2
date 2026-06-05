package com.erp;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * Entry point for the ERP modular-monolith API.
 *
 * <p>Module boundaries under {@code com.erp.modules.*} are enforced by ArchUnit
 * (see {@code ModuleBoundaryTest}); cross-cutting infrastructure lives under
 * {@code com.erp.platform.*} and REST controllers under {@code com.erp.api.*}.
 */
@SpringBootApplication
public class ErpApplication {

    public static void main(String[] args) {
        SpringApplication.run(ErpApplication.class, args);
    }
}
