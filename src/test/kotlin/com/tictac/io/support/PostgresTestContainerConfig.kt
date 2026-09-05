package com.tictac.io.support

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.postgresql.PostgreSQLContainer

/**
 * Real PostgreSQL for tests. @ServiceConnection wires the container's JDBC details
 * into the context, so Flyway migrations and Hibernate's schema validation run against
 * the same database engine we deploy on - an in-memory database would not catch a
 * mismatch between a migration and an entity mapping.
 */
@TestConfiguration(proxyBeanMethods = false)
class PostgresTestContainerConfig {

    @Bean
    @ServiceConnection
    fun postgresContainer(): PostgreSQLContainer = PostgreSQLContainer(POSTGRES_IMAGE)

    companion object {
        const val POSTGRES_IMAGE = "postgres:17-alpine"
    }
}
