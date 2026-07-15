package dev.ragplatform.infrastructure.security;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;

import javax.sql.DataSource;

/**
 * Envolve o bean 'dataSource' com RlsDataSourceWrapper em tempo de inicialização.
 *
 * BeanPostProcessor é instanciado antes dos beans regulares do Spring, portanto
 * não pode ter @Autowired de beans comuns. RlsDataSourceWrapper não tem dependências,
 * então o padrão é seguro.
 *
 * HIGHEST_PRECEDENCE garante que o wrapper acontece antes de qualquer outro
 * pós-processador que possa depender do DataSource (ex: Flyway, JPA, HikariCP metrics).
 */
@Configuration
@Order(Ordered.HIGHEST_PRECEDENCE)
public class RlsConfig implements BeanPostProcessor {

    @Override
    public Object postProcessAfterInitialization(Object bean, String beanName) throws BeansException {
        if ("dataSource".equals(beanName)
                && bean instanceof DataSource ds
                && !(bean instanceof RlsDataSourceWrapper)) {
            return new RlsDataSourceWrapper(ds);
        }
        return bean;
    }
}
