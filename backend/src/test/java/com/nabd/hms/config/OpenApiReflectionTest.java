package com.nabd.hms.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * OPS-034: no Docker. Walks every @RestController by reflection and checks the committed spec (and the hand-written
 * api/openapi.yaml) against it: nothing implemented is missing, nothing documented is phantom.
 */
class OpenApiReflectionTest {

    static final Set<String> VERBS = Set.of("get", "post", "put", "patch", "delete");

    static Set<String> implemented() throws Exception {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        Set<String> ops = new TreeSet<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.nabd.hms")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            RequestMapping base = AnnotatedElementUtils.findMergedAnnotation(c, RequestMapping.class);
            String[] bases = base == null || base.path().length == 0 ? new String[] {""} : base.path();
            for (Method m : c.getDeclaredMethods()) {
                RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
                if (rm == null) continue;
                String[] own = rm.path().length == 0 ? new String[] {""} : rm.path();
                for (String b : bases) for (String o : own) for (var verb : rm.method())
                    ops.add(verb.name().toLowerCase() + " " + (b + o).replaceAll("\\{([^}:]+):[^}]*}", "{$1}"));
            }
        }
        return ops;
    }

    @SuppressWarnings("unchecked")
    static Set<String> documented(String file, String prefix) throws Exception {
        Map<String, Map<String, Object>> paths = (Map<String, Map<String, Object>>)
                ((Map<String, Object>) new org.yaml.snakeyaml.Yaml().load(Files.readString(Path.of(file)))).get("paths");
        Set<String> ops = new TreeSet<>();
        paths.forEach((p, item) -> item.keySet().stream().filter(VERBS::contains).forEach(v -> ops.add(v + " " + prefix + p)));
        return ops;
    }

    @Test
    void committedCoreSpecHasEveryImplementedOperationAndNothingElse() throws Exception {
        Set<String> impl = implemented();
        assertTrue(impl.size() >= 264, "reflection found only " + impl.size());
        assertEquals(impl, documented("../api/openapi/core.yaml", ""));
    }

    @Test
    void handWrittenSpecHasNoPhantomOperations() throws Exception {
        Set<String> phantom = new TreeSet<>(documented("../api/openapi.yaml", "/v1"));
        phantom.removeAll(implemented());
        assertTrue(phantom.isEmpty(), "documented but not implemented: " + phantom);
    }
}
