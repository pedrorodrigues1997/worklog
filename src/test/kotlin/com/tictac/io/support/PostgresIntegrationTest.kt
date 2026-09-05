package com.tictac.io.support

import com.tictac.io.user.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

/**
 * Base for tests that need the full application context and a real database.
 * All subclasses share one Spring context (and therefore one container) because they
 * share identical context configuration.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgresTestContainerConfig::class)
abstract class PostgresIntegrationTest {

    @Autowired
    protected lateinit var userRepository: UserRepository

    /** Tests commit, so state has to be cleared explicitly between them. */
    @BeforeEach
    fun clearUsers() {
        userRepository.deleteAll()
    }
}
