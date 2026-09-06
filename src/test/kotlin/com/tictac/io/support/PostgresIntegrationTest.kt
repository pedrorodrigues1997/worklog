package com.tictac.io.support

import com.tictac.io.authentication.oauth.OAuthLoginCodeRepository
import com.tictac.io.authentication.oauth.UserIdentityRepository
import com.tictac.io.authentication.token.RefreshTokenRepository
import com.tictac.io.organization.OrganizationMemberRepository
import com.tictac.io.billing.BillingCustomerRepository
import com.tictac.io.billing.BillingWebhookEventRepository
import com.tictac.io.billing.SubscriptionRepository
import com.tictac.io.organization.OrganizationInvitationRepository
import com.tictac.io.organization.OrganizationRepository
import com.tictac.io.project.ProjectCategoryRepository
import com.tictac.io.project.ProjectMemberRepository
import com.tictac.io.project.ProjectRepository
import com.tictac.io.timetracking.TimeEntryRepository
import com.tictac.io.user.UserRepository
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.servlet.MockMvc

/**
 * Base for tests that need the full application context and a real database.
 * All subclasses share one Spring context (and therefore one container) because they
 * share identical context configuration.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(PostgresTestContainerConfig::class)
abstract class PostgresIntegrationTest {

    @Autowired
    protected lateinit var mockMvc: MockMvc

    @Autowired
    protected lateinit var userRepository: UserRepository

    @Autowired
    protected lateinit var refreshTokenRepository: RefreshTokenRepository

    @Autowired
    protected lateinit var userIdentityRepository: UserIdentityRepository

    @Autowired
    protected lateinit var oAuthLoginCodeRepository: OAuthLoginCodeRepository

    @Autowired
    protected lateinit var organizationRepository: OrganizationRepository

    @Autowired
    protected lateinit var organizationMemberRepository: OrganizationMemberRepository

    @Autowired
    protected lateinit var organizationInvitationRepository: OrganizationInvitationRepository

    @Autowired
    protected lateinit var projectRepository: ProjectRepository

    @Autowired
    protected lateinit var projectMemberRepository: ProjectMemberRepository

    @Autowired
    protected lateinit var projectCategoryRepository: ProjectCategoryRepository

    @Autowired
    protected lateinit var timeEntryRepository: TimeEntryRepository

    @Autowired
    protected lateinit var billingCustomerRepository: BillingCustomerRepository

    @Autowired
    protected lateinit var subscriptionRepository: SubscriptionRepository

    @Autowired
    protected lateinit var billingWebhookEventRepository: BillingWebhookEventRepository

    /** Tests commit, so state has to be cleared explicitly between them. Children first. */
    @BeforeEach
    fun clearDatabase() {
        oAuthLoginCodeRepository.deleteAll()
        userIdentityRepository.deleteAll()
        refreshTokenRepository.deleteAll()
        billingWebhookEventRepository.deleteAll()
        subscriptionRepository.deleteAll()
        billingCustomerRepository.deleteAll()
        timeEntryRepository.deleteAll()
        projectCategoryRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        organizationInvitationRepository.deleteAll()
        organizationMemberRepository.deleteAll()
        organizationRepository.deleteAll()
        userRepository.deleteAll()
    }
}
