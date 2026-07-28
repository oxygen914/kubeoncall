package com.kubeoncall;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noFields;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaField;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

@AnalyzeClasses(packages = "com.kubeoncall", importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureRulesTest {

    // Core domain packages must not depend on the web layer. `dependOnClassesThat` catches real
    // core→web leaks (e.g. a domain service importing a web exception); it is the rule that found
    // the AlarmCommandService→V1ApiException leak in WBS-6. The original 8 packages are covered here.
    @ArchTest
    static final ArchRule coreBusinessDomainsDoNotDependOnWeb = noClasses()
            .that()
            .resideInAnyPackage(
                    "com.kubeoncall.agent..",
                    "com.kubeoncall.alarm..",
                    "com.kubeoncall.approval..",
                    "com.kubeoncall.memory..",
                    "com.kubeoncall.rag..",
                    "com.kubeoncall.skill..",
                    "com.kubeoncall.tool..",
                    "com.kubeoncall.workflow..",
                    "com.kubeoncall.sandbox..")
            .should()
            .dependOnClassesThat()
            .resideInAnyPackage("..web..");

    // WBS-1..12 added new core packages (identity/audit/idempotency/migration/knowledge/realtime/
    // task/worker/observability). For these we assert a tighter, false-positive-free invariant: no
    // field of a core class may have a raw type from the web layer. Generic signature analysis
    // (`dependOnClassesThat`) misattributes web view classes that reference core records as core→web
    // leaks; the field-type check avoids that while still catching a real leaked web dependency held
    // as state. A `grep "import com.kubeoncall.web"` over these packages currently returns zero, which
    // this rule makes a permanent gate.
    static final DescribedPredicate<JavaClass> webType = new DescribedPredicate<JavaClass>("reside in the web layer") {
        @Override
        public boolean test(JavaClass javaClass) {
            String name = javaClass.getName();
            return name.startsWith("com.kubeoncall.web");
        }
    };

    static final DescribedPredicate<JavaField> inNewCorePackage =
            new DescribedPredicate<JavaField>("declared in a WBS core package") {
                @Override
                public boolean test(JavaField field) {
                    String pkg = field.getOwner().getPackageName();
                    return pkg.startsWith("com.kubeoncall.identity")
                            || pkg.startsWith("com.kubeoncall.audit")
                            || pkg.startsWith("com.kubeoncall.idempotency")
                            || pkg.startsWith("com.kubeoncall.migration")
                            || pkg.startsWith("com.kubeoncall.knowledge")
                            || pkg.startsWith("com.kubeoncall.realtime")
                            || pkg.startsWith("com.kubeoncall.task")
                            || pkg.startsWith("com.kubeoncall.worker")
                            || pkg.startsWith("com.kubeoncall.observability")
                            || pkg.startsWith("com.kubeoncall.sandbox");
                }
            };

    @ArchTest
    static final ArchRule newCorePackagesHoldNoWebFields = noFields()
            .that(inNewCorePackage)
            .should()
            .haveRawType(webType)
            .because("WBS core packages must not hold web-layer types as field state");
}
