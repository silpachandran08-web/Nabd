package com.nabd.hms.architecture;

import com.tngtech.archunit.core.domain.Dependency;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.lang.ArchCondition;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.ConditionEvents;
import com.tngtech.archunit.lang.SimpleConditionEvent;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.assignableTo;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.methods;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

/** OPS-033 / ARCH §11.2 rule set. Modules are the packages directly under com.nabd.hms; layers go by class-name suffix
 * (the code base is flat per module). The gate (report / new code / all) lives in scripts/ci/arch.py, not here. */
final class ArchitectureRules {
    static final String ROOT = "com.nabd.hms";
    static final String[] KERNEL = {ROOT + ".shared..", ROOT + ".common.."};
    private static final Pattern MODULE = Pattern.compile(Pattern.quote(ROOT) + "\\.(\\w+)\\.");
    private static final Pattern SLICE = Pattern.compile("(?:Slice |Module ')(\\w+)");

    private ArchitectureRules() {}

    static Map<String, ArchRule> rules() {
        Map<String, ArchRule> r = new LinkedHashMap<>();
        r.put("no-module-cycles", slices().matching(ROOT + ".(*)..").should().beFreeOfCycles());
        r.put("internal-only-from-own-module", classes().that().resideInAPackage(ROOT + ".*.internal..")
                .should(onlyBeAccessedFromOwnModule()).allowEmptyShould(true));
        r.put("controllers-no-repository-or-jdbc", noClasses().that().haveSimpleNameEndingWith("Controller")
                .or().areAnnotatedWith(RestController.class)
                .should().dependOnClassesThat().haveSimpleNameEndingWith("Repository")
                .orShould().dependOnClassesThat(assignableTo("org.springframework.jdbc.core.JdbcOperations"))
                .orShould().dependOnClassesThat(assignableTo("org.springframework.jdbc.core.namedparam.NamedParameterJdbcOperations")));
        r.put("transactional-only-in-services", methods().that().areAnnotatedWith(Transactional.class)
                .should().beDeclaredInClassesThat().haveSimpleNameEndingWith("Service").allowEmptyShould(true));
        r.put("transactional-classes-are-services", classes().that().areAnnotatedWith(Transactional.class)
                .should().haveSimpleNameEndingWith("Service").allowEmptyShould(true));
        r.put("http-clients-only-in-integration", noClasses().that().resideOutsideOfPackage(ROOT + ".*.integration..")
                .should().dependOnClassesThat(assignableTo("org.springframework.web.client.RestTemplate"))
                .orShould().dependOnClassesThat(assignableTo("org.springframework.web.client.RestClient"))
                .orShould().dependOnClassesThat(assignableTo("org.springframework.web.reactive.function.client.WebClient"))
                .orShould().dependOnClassesThat(assignableTo("java.net.http.HttpClient")));
        r.put("shared-kernel-is-leaf", noClasses().that().resideInAnyPackage(KERNEL)
                .should(dependOnModuleOutside("shared", "common")).allowEmptyShould(true));
        r.put("platform-depends-only-on-kernel", noClasses().that().resideInAPackage(ROOT + ".platform..")
                .should(dependOnModuleOutside("platform", "shared", "common")).allowEmptyShould(true));
        r.put("clinic-modules-do-not-use-platform", noClasses().that().resideInAPackage(ROOT + "..")
                .and().resideOutsideOfPackages(ROOT + ".platform..", ROOT + ".config..")
                .should().dependOnClassesThat().resideInAPackage(ROOT + ".platform..").allowEmptyShould(true));
        return r;
    }

    /** One record per violation: rule, module, detail (first line only; line numbers kept, arch.py strips them for the baseline key). */
    static List<Map<String, String>> evaluate(JavaClasses classes) {
        List<Map<String, String>> out = new ArrayList<>();
        rules().forEach((name, rule) -> {
            for (String d : rule.evaluate(classes).getFailureReport().getDetails()) {
                out.add(Map.of("tool", "archunit", "rule", name, "module", moduleOf(d), "detail", headline(d)));
            }
        });
        return out;
    }

    /** A violation's headline: lines up to the numbered dependency list (a cycle spans several lines), whitespace collapsed. */
    static String headline(String d) {
        StringBuilder b = new StringBuilder();
        for (String l : d.split("\\R")) {
            if (l.matches("\\s*\\d+\\..*")) break;
            b.append(' ').append(l.trim());
        }
        return b.toString().trim();
    }

    static String moduleOf(String detail) {
        Matcher s = SLICE.matcher(detail);
        if (s.find()) return s.group(1);
        Matcher m = MODULE.matcher(detail);
        return m.find() ? m.group(1) : "app";
    }

    static String moduleOf(JavaClass c) {
        Matcher m = MODULE.matcher(c.getName());
        return m.find() ? m.group(1) : "app";
    }

    private static ArchCondition<JavaClass> onlyBeAccessedFromOwnModule() {
        return new ArchCondition<>("only be accessed from their own module") {
            @Override public void check(JavaClass item, ConditionEvents events) {
                for (Dependency d : item.getDirectDependenciesToSelf()) {
                    if (!moduleOf(d.getOriginClass()).equals(moduleOf(item))) events.add(SimpleConditionEvent.violated(d, d.getDescription()));
                }
            }
        };
    }

    private static ArchCondition<JavaClass> dependOnModuleOutside(String... allowed) {
        List<String> ok = List.of(allowed);
        return new ArchCondition<>("depend on a module other than " + ok) {
            @Override public void check(JavaClass item, ConditionEvents events) {
                for (Dependency d : item.getDirectDependenciesFromSelf()) {
                    JavaClass t = d.getTargetClass();
                    if (t.getPackageName().startsWith(ROOT + ".") && !ok.contains(moduleOf(t))) {
                        events.add(SimpleConditionEvent.satisfied(d, d.getDescription()));  // satisfied + noClasses() = violation
                    }
                }
            }
        };
    }
}
