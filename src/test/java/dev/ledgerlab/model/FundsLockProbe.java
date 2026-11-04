package dev.ledgerlab.model;

import dev.ledgerlab.ledger.Ledger;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import org.springframework.beans.factory.config.BeanPostProcessor;

/** Test-only decorator; the real PostgreSQL funds lock and all ledger operations stay intact. */
final class FundsLockProbe implements BeanPostProcessor {
    volatile Runnable before = () -> {};
    volatile Runnable after = () -> {};

    public Object postProcessAfterInitialization(Object bean, String name) {
        if (!(bean instanceof Ledger)) return bean;
        return Proxy.newProxyInstance(
                Ledger.class.getClassLoader(), new Class<?>[] {Ledger.class}, (proxy, method, args) -> {
                    boolean lock = method.getName().equals("lockFunds");
                    if (lock) before.run();
                    try {
                        Object result = method.invoke(bean, args);
                        if (lock) after.run();
                        return result;
                    } catch (InvocationTargetException e) {
                        throw e.getCause();
                    }
                });
    }
}
