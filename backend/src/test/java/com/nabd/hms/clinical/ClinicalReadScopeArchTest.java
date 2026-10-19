package com.nabd.hms.clinical;

import org.junit.jupiter.api.Test;
import org.springframework.asm.ClassReader;
import org.springframework.asm.ClassVisitor;
import org.springframework.asm.MethodVisitor;
import org.springframework.asm.Opcodes;
import org.springframework.asm.Type;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.context.annotation.ClassPathScanningCandidateComponentProvider;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.core.type.filter.AnnotationTypeFilter;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * IDC-001 architecture rule (no Docker; ArchUnit is not a dependency, so this reads the bytecode with Spring's bundled ASM):
 * every @GetMapping in a com.nabd.hms.clinical.. controller must resolve the caller (ClinicalReadScope.resolve) AND pass the
 * ReadScope on to a method that takes one (a require* probe or a gated service/repository read), unless it is marked
 * {@link ClinicalReadScope.NoPatientData}. A new clinical GET without the fragment fails the build.
 */
class ClinicalReadScopeArchTest {

    private static final String READ_SCOPE = Type.getDescriptor(ReadScope.class);
    private static final String GATE_OWNER = Type.getInternalName(ClinicalReadScope.class);

    /** Returns "Controller#method" for each clinical GET that does not go through the read scope. */
    static List<String> violations(Class<?> controller) throws IOException {
        List<String> out = new ArrayList<>();
        Set<String> gated = gatedMethods(controller);
        for (Method m : controller.getDeclaredMethods()) {
            RequestMapping rm = AnnotatedElementUtils.findMergedAnnotation(m, RequestMapping.class);
            boolean isGet = rm != null && List.of(rm.method()).contains(org.springframework.web.bind.annotation.RequestMethod.GET);
            if (!isGet || m.isAnnotationPresent(ClinicalReadScope.NoPatientData.class)) continue;
            if (!gated.contains(m.getName() + Type.getMethodDescriptor(m))) {
                out.add(controller.getSimpleName() + "#" + m.getName());
            }
        }
        return out;
    }

    /** Methods whose bytecode calls ClinicalReadScope.resolve and also some method taking a ReadScope argument. */
    private static Set<String> gatedMethods(Class<?> c) throws IOException {
        Set<String> gated = new HashSet<>();
        try (InputStream in = c.getClassLoader().getResourceAsStream(c.getName().replace('.', '/') + ".class")) {
            new ClassReader(in).accept(new ClassVisitor(Opcodes.ASM9) {
                @Override
                public MethodVisitor visitMethod(int access, String name, String desc, String sig, String[] ex) {
                    boolean[] seen = new boolean[2];
                    return new MethodVisitor(Opcodes.ASM9) {
                        @Override
                        public void visitMethodInsn(int op, String owner, String mName, String mDesc, boolean itf) {
                            if (owner.equals(GATE_OWNER) && mName.equals("resolve")) seen[0] = true;
                            String args = mDesc.substring(0, mDesc.indexOf(')'));
                            if (args.contains(READ_SCOPE)) seen[1] = true;
                        }

                        @Override
                        public void visitEnd() {
                            if (seen[0] && seen[1]) gated.add(name + desc);
                        }
                    };
                }
            }, ClassReader.SKIP_DEBUG);
        }
        return gated;
    }

    static List<Class<?>> clinicalControllers() throws ClassNotFoundException {
        var scanner = new ClassPathScanningCandidateComponentProvider(false);
        scanner.addIncludeFilter(new AnnotationTypeFilter(RestController.class));
        List<Class<?>> out = new ArrayList<>();
        for (BeanDefinition bd : scanner.findCandidateComponents("com.nabd.hms.clinical")) {
            Class<?> c = Class.forName(bd.getBeanClassName());
            if (c.getProtectionDomain().getCodeSource().getLocation().getPath().endsWith("/test-classes/")) continue;
            out.add(c);
        }
        return out;
    }

    @Test
    void everyClinicalGetGoesThroughTheReadScope() throws Exception {
        List<Class<?>> controllers = clinicalControllers();
        assertThat(controllers).hasSizeGreaterThanOrEqualTo(11);
        List<String> bad = new ArrayList<>();
        for (Class<?> c : controllers) bad.addAll(violations(c));
        assertThat(bad).as("clinical GETs without ClinicalReadScope (gate them or mark @NoPatientData)").isEmpty();
    }

    // ── self-check: the rule really catches an ungated GET ──

    // Deliberately not @RestController: component scanning would otherwise mount them in every test context.
    static class UngatedController {
        @GetMapping("/{patientId}")
        public String leak(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId) {
            return patientId.toString();
        }
    }

    static class GatedController {
        private final ClinicalReadScope scopes = null;

        @GetMapping("/{patientId}")
        public String ok(@AuthenticationPrincipal Jwt jwt, @PathVariable UUID patientId) {
            ReadScope s = scopes.resolve(jwt);
            scopes.requirePatient(s, patientId);
            return "";
        }

        @GetMapping("/resolved-but-unused")
        public String resolvedOnly(@AuthenticationPrincipal Jwt jwt) {
            return String.valueOf(scopes.resolve(jwt));
        }
    }

    @Test
    void ruleFailsAnUngatedGetAndAResolvedButUnusedScope() throws Exception {
        assertThat(violations(UngatedController.class)).containsExactly("UngatedController#leak");
        assertThat(violations(GatedController.class)).containsExactly("GatedController#resolvedOnly");
    }
}
