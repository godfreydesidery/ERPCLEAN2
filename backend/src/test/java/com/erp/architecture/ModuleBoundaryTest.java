package com.erp.architecture;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import org.junit.jupiter.api.Test;

/**
 * Enforces the modular-monolith boundaries (PROJECT-CONVENTIONS §2, ARCHITECTURE §3).
 *
 * <p>Rules grow as modules land. Today (Slice 0) the package skeleton exists and the layering and
 * controller→repository rules are active so they guard every later slice from day one.
 *
 * <ul>
 *   <li>Controllers ({@code com.erp.api..}) may not access repositories directly.</li>
 *   <li>Layer order is controller → service → repository → domain.</li>
 *   <li>(Added per-module) a module's {@code domain}/{@code service}/{@code repository} are not
 *       imported by another module — only {@code domain.dto}/{@code domain.enums} cross.</li>
 * </ul>
 */
class ModuleBoundaryTest {

    private static final JavaClasses CLASSES = new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .importPackages("com.erp");

    @Test
    void controllersDoNotAccessRepositories() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("com.erp.api..")
                .should().dependOnClassesThat().resideInAPackage("..repository..")
                .as("REST controllers must go through services, not repositories")
                .allowEmptyShould(true); // no repositories exist yet in Slice 0
        rule.check(CLASSES);
    }

    @Test
    void controllersAreNamedAndPlacedConsistently() {
        ArchRule rule = classes()
                .that().areAnnotatedWith(org.springframework.web.bind.annotation.RestController.class)
                .should().resideInAPackage("com.erp.api..")
                .as("@RestController classes live under com.erp.api")
                .allowEmptyShould(true);
        rule.check(CLASSES);
    }

    @Test
    void servicesDoNotDependOnControllers() {
        ArchRule rule = noClasses()
                .that().resideInAPackage("..service..")
                .should().dependOnClassesThat().resideInAPackage("com.erp.api..")
                .as("services must not depend on the web layer")
                .allowEmptyShould(true);
        rule.check(CLASSES);
    }
}
