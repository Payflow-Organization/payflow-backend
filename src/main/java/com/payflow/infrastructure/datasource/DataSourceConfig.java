package com.payflow.infrastructure.datasource;

import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.jdbc.autoconfigure.DataSourceProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

import javax.sql.DataSource;
import java.util.Map;

@Configuration
public class DataSourceConfig {

    @Bean
    @ConfigurationProperties("spring.datasource.write")
    public DataSourceProperties writeDataSourceProperties() {
        DataSourceProperties props = new DataSourceProperties();
        return props;
    }

    @Bean
    @ConfigurationProperties("spring.datasource.write.hikari")
    public HikariDataSource writeDataSource(DataSourceProperties writeDataSourceProperties) {
        HikariDataSource ds = writeDataSourceProperties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        ds.setPoolName("PrimaryPool");
        return ds;
    }

    @Bean
    @ConfigurationProperties("spring.datasource.read")
    public DataSourceProperties readDataSourceProperties() {
        return new DataSourceProperties();
    }

    @Bean
    @ConfigurationProperties("spring.datasource.read.hikari")
    public HikariDataSource readDataSource(DataSourceProperties readDataSourceProperties) {
        HikariDataSource ds = readDataSourceProperties
                .initializeDataSourceBuilder()
                .type(HikariDataSource.class)
                .build();
        ds.setPoolName("SecondaryPool");
        return ds;
    }

    @Bean
    @Primary
    public DataSource dataSource(HikariDataSource writeDataSource, HikariDataSource readDataSource) {
        var routing = new RoutingDataSource();
        routing.setTargetDataSources(Map.of("write", writeDataSource, "read", readDataSource));
        routing.setDefaultTargetDataSource(writeDataSource);
        routing.afterPropertiesSet();
        return routing;
    }
}
