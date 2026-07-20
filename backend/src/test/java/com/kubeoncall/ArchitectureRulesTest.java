package com.kubeoncall;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.kubeoncall", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    @ArchTest
    static final ArchRule coreBusinessDomainsDoNotDependOnWeb = noClasses()
            .that()
            .resideInAnyPackage(
                    "..agent..",
                    "..alarm..",
                    "..approval..",
                    "..memory..",
                    "..rag..",
                    "..skill..",
                    "..tool..",
                    "..workflow..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..web..");

    @ArchTest
    static final ArchRule webDoesNotDependOnInfrastructureClients = noClasses()
            .that()
            .resideInAPackage("..web..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage(
                    "org.springframework.data.redis..", "org.springframework.data.elasticsearch..", "io.minio..");
}
