package com.tictac.io

import com.tictac.io.support.PostgresIntegrationTest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.jdbc.core.JdbcTemplate

/**
 * The context only starts if Flyway migrated a real PostgreSQL database and Hibernate's
 * `ddl-auto=validate` then agreed that the entity mappings match the resulting schema.
 * That makes context startup itself the assertion that migrations and mappings are in sync.
 */
class IoApplicationTests : PostgresIntegrationTest() {

    @Autowired
    private lateinit var jdbcTemplate: JdbcTemplate

    @Test
    fun `flyway applied the initial migration successfully`() {
        val applied = jdbcTemplate.queryForList(
            "SELECT version, description, success FROM flyway_schema_history ORDER BY installed_rank",
        )

        assertThat(applied).isNotEmpty
        assertThat(applied).allSatisfy { row -> assertThat(row["success"]).isEqualTo(true) }
        assertThat(applied.map { it["version"] }).contains("1")
    }

    @Test
    fun `the users table has the agreed columns`() {
        val columns = jdbcTemplate.queryForList(
            "SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name = 'users'",
        ).associate { it["column_name"] as String to it["is_nullable"] as String }

        assertThat(columns.keys).containsExactlyInAnyOrder(
            "id", "first_name", "last_name", "email", "password_hash", "created_at", "deleted_at",
        )
        assertThat(columns["password_hash"]).isEqualTo("YES")
        assertThat(columns["deleted_at"]).isEqualTo("YES")
        assertThat(columns["email"]).isEqualTo("NO")
        assertThat(columns["created_at"]).isEqualTo("NO")
    }

    @Test
    fun `the refresh tokens table has the agreed columns and constraints`() {
        val columns = jdbcTemplate.queryForList(
            "SELECT column_name, is_nullable FROM information_schema.columns WHERE table_name = 'refresh_tokens'",
        ).associate { it["column_name"] as String to it["is_nullable"] as String }

        assertThat(columns.keys).containsExactlyInAnyOrder(
            "id", "user_id", "token_hash", "issued_at", "expires_at", "revoked_at", "replaced_by_id",
        )
        assertThat(columns["token_hash"]).isEqualTo("NO")
        assertThat(columns["revoked_at"]).isEqualTo("YES")
        assertThat(columns["replaced_by_id"]).isEqualTo("YES")

        val constraints = jdbcTemplate.queryForList(
            """
            SELECT constraint_name, constraint_type FROM information_schema.table_constraints
            WHERE table_name = 'refresh_tokens'
            """.trimIndent(),
        ).map { it["constraint_name"] as String }

        assertThat(constraints).contains("fk_refresh_tokens_user")
    }

    @Test
    fun `users is left untouched by the refresh token migration`() {
        val userColumns = jdbcTemplate.queryForList(
            "SELECT column_name FROM information_schema.columns WHERE table_name = 'users'",
        ).map { it["column_name"] as String }

        assertThat(userColumns).containsExactlyInAnyOrder(
            "id", "first_name", "last_name", "email", "password_hash", "created_at", "deleted_at",
        )
    }
}
