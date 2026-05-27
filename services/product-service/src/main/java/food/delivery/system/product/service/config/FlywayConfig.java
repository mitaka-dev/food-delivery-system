package food.delivery.system.product.service.config;

import org.flywaydb.core.Flyway;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.jdbc.SchemaManagement;
import org.springframework.boot.jdbc.SchemaManagementProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.util.Arrays;

/**
 * Spring Boot 4.0 dropped Flyway auto-configuration from spring-boot-autoconfigure.
 * This class wires Flyway manually and enforces that the EntityManagerFactory
 * waits for migrations to complete before Hibernate validates the schema.
 *
 * Three mechanisms work together:
 *  1. flywayMigrator bean — runs migrate() on construction (depends on DataSource)
 *  2. flywayDependencyEnforcer — BeanDefinitionRegistryPostProcessor that adds
 *     @DependsOn("flywayMigrator") to entityManagerFactory at startup
 *  3. flywaySchemaManagementProvider — signals Boot's HibernateJpaConfiguration
 *     that schema is externally managed (suppresses create-drop default on embedded DBs)
 *
 * Set spring.flyway.enabled=false to skip migrations (used in tests with embedded H2).
 */
@Configuration
@ConditionalOnProperty(name = "spring.flyway.enabled", havingValue = "true", matchIfMissing = true)
public class FlywayConfig {

    @Bean
    public Flyway flywayMigrator(DataSource dataSource) {
        Flyway flyway = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration/product-service")
                .load();
        flyway.migrate();
        return flyway;
    }

    @Bean
    public SchemaManagementProvider flywaySchemaManagementProvider(Flyway flyway) {
        return dataSource -> SchemaManagement.MANAGED;
    }

    @Bean
    public static BeanDefinitionRegistryPostProcessor flywayDependencyEnforcer() {
        return new BeanDefinitionRegistryPostProcessor() {
            @Override
            public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
                addDependsOn(registry, "entityManagerFactory", "flywayMigrator");
            }

            @Override
            public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) throws BeansException {
            }

            private void addDependsOn(BeanDefinitionRegistry registry, String beanName, String dependency) {
                if (!registry.containsBeanDefinition(beanName)) {
                    return;
                }
                BeanDefinition def = registry.getBeanDefinition(beanName);
                String[] existing = def.getDependsOn();
                if (existing == null) {
                    def.setDependsOn(dependency);
                } else if (!Arrays.asList(existing).contains(dependency)) {
                    String[] updated = Arrays.copyOf(existing, existing.length + 1);
                    updated[existing.length] = dependency;
                    def.setDependsOn(updated);
                }
            }
        };
    }
}
