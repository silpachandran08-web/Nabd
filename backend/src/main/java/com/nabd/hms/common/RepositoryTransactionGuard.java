package com.nabd.hms.common;

import org.aopalliance.intercept.MethodInterceptor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.lang.reflect.Modifier;

/**
 * PLT-019, tests only (nabd.tenancy.strict-repositories=true in src/test/resources): a *Repository call made outside
 * a transaction runs in one whose app.tenant_id is a non-UUID sentinel, so any RLS policy it hits (app_tenant_id())
 * fails in Postgres itself, and that failure is rethrown as IllegalStateException. Non-RLS queries are unaffected.
 * Production fails closed instead (RLS returns no rows). Inside a bound transaction it also runs
 * {@link TenantContext#verifyBinding()}: a raw set_config that switched the tenant is refused.
 */
@Component
@ConditionalOnProperty("nabd.tenancy.strict-repositories")
class RepositoryTransactionGuard implements BeanPostProcessor {

    static final String SENTINEL = "outside-a-transaction";

    private final ObjectProvider<PlatformTransactionManager> txManager;
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ObjectProvider<TenantContext> tenantContext;

    RepositoryTransactionGuard(ObjectProvider<PlatformTransactionManager> txManager, ObjectProvider<JdbcTemplate> jdbc,
                               ObjectProvider<TenantContext> tenantContext) {
        this.txManager = txManager;
        this.jdbc = jdbc;
        this.tenantContext = tenantContext;
    }

    @Override
    public Object postProcessAfterInitialization(Object bean, String name) {
        Class<?> c = AopUtils.getTargetClass(bean);
        if (!c.getName().startsWith("com.nabd.hms.") || !c.getSimpleName().endsWith("Repository") || Modifier.isFinal(c.getModifiers())) {
            return bean;
        }
        MethodInterceptor guard = inv -> {
            if (TransactionSynchronizationManager.isActualTransactionActive()) {
                tenantContext.getObject().verifyBinding(); // a raw set_config switched the bound tenant: refuse
                return inv.proceed();
            }
            Object[] out = new Object[1];
            try {
                new TransactionTemplate(txManager.getObject()).executeWithoutResult(s -> {
                    jdbc.getObject().queryForObject("SELECT set_config('app.tenant_id', '" + SENTINEL + "', true)", String.class);
                    try {
                        out[0] = inv.proceed();
                    } catch (RuntimeException | Error e) {
                        throw e;
                    } catch (Throwable t) {
                        throw new IllegalStateException(t);
                    }
                });
            } catch (RuntimeException e) {
                for (Throwable t = e; t != null; t = t.getCause()) {
                    if (String.valueOf(t.getMessage()).contains(SENTINEL)) {
                        throw new IllegalStateException(c.getSimpleName() + "." + inv.getMethod().getName()
                                + " touched an RLS table outside a transaction (wrap the caller in @Transactional or TenantScope.run)", e);
                    }
                }
                throw e;
            }
            return out[0];
        };
        if (bean instanceof Advised advised) {
            advised.addAdvice(0, guard);
            return bean;
        }
        ProxyFactory pf = new ProxyFactory(bean);
        pf.setProxyTargetClass(true);
        pf.addAdvice(guard);
        return pf.getProxy(c.getClassLoader());
    }
}
