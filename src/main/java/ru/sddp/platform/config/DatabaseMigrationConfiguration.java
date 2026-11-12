package ru.sddp.platform.config;

import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.config.BeanFactoryPostProcessor;
import org.springframework.beans.factory.support.AbstractBeanDefinition;

@Configuration
public class DatabaseMigrationConfiguration {
    @Bean(initMethod = "migrate")
    public Flyway flyway(DataSource dataSource) {
        return Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").baselineOnMigrate(false).load();
    }

    @Bean
    public static BeanFactoryPostProcessor flywayBeforeJpa() {
        return beanFactory -> {
            if (beanFactory.containsBeanDefinition("entityManagerFactory")) {
                AbstractBeanDefinition definition = (AbstractBeanDefinition) beanFactory.getBeanDefinition("entityManagerFactory");
                definition.setDependsOn("flyway");
            }
        };
    }
}
