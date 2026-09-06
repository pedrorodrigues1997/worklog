package com.tictac.io.organization

import com.tictac.io.organization.OrganizationRole.ADMIN
import com.tictac.io.organization.OrganizationRole.MEMBER
import com.tictac.io.organization.OrganizationRole.OWNER
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * The membership policy, exhaustively. Both predicates take a role, so the full input
 * space is nine pairs each and there is no reason to sample it.
 */
@DisplayName("OrganizationRole: the membership policy")
class OrganizationRoleTest {

    @Test
    fun `a role never outranks itself, which is what stops self-promotion and self-removal`() {
        OrganizationRole.entries.forEach { assertThat(it.outranks(it)).isFalse() }
    }

    @Test
    fun `nobody outranks the owner`() {
        OrganizationRole.entries.forEach { assertThat(it.outranks(OWNER)).isFalse() }
    }

    @Test
    fun `the owner outranks everyone else`() {
        assertThat(OWNER.outranks(ADMIN)).isTrue()
        assertThat(OWNER.outranks(MEMBER)).isTrue()
    }

    @Test
    fun `an admin outranks members only`() {
        assertThat(ADMIN.outranks(MEMBER)).isTrue()
        assertThat(ADMIN.outranks(ADMIN)).isFalse()
        assertThat(ADMIN.outranks(OWNER)).isFalse()
    }

    @Test
    fun `a member outranks nobody`() {
        OrganizationRole.entries.forEach { assertThat(MEMBER.outranks(it)).isFalse() }
    }

    @Test
    fun `owner is not assignable by anyone`() {
        OrganizationRole.entries.forEach { assertThat(it.canAssign(OWNER)).isFalse() }
    }

    @Test
    fun `the owner can hand out every role below their own`() {
        assertThat(OWNER.canAssign(ADMIN)).isTrue()
        assertThat(OWNER.canAssign(MEMBER)).isTrue()
    }

    @Test
    fun `an admin can hand out admin and member, but nothing above themselves`() {
        assertThat(ADMIN.canAssign(ADMIN)).isTrue()
        assertThat(ADMIN.canAssign(MEMBER)).isTrue()
        assertThat(ADMIN.canAssign(OWNER)).isFalse()
    }

    @Test
    fun `a member can hand out nothing but member, and gets nowhere without outranking anyone`() {
        assertThat(MEMBER.canAssign(MEMBER)).isTrue()
        assertThat(MEMBER.canAssign(ADMIN)).isFalse()
        assertThat(MEMBER.canAssign(OWNER)).isFalse()

        // canAssign alone is not the gate: acting on anybody also requires outranking
        // them, and a member outranks nobody, so the pair is what makes this unreachable.
        OrganizationRole.entries.forEach { assertThat(MEMBER.outranks(it)).isFalse() }
    }

    @Test
    fun `no role can grant authority it does not itself hold`() {
        // The escalation property, stated directly: for every caller and every target
        // they may act on, the role they may assign is never above their own.
        OrganizationRole.entries.forEach { caller ->
            OrganizationRole.entries
                .filter { caller.outranks(it) }
                .forEach { _ ->
                    OrganizationRole.entries
                        .filter { caller.canAssign(it) }
                        .forEach { assignable -> assertThat(assignable.outranks(caller)).isFalse() }
                }
        }
    }

    @Test
    fun `the three roles are the whole set`() {
        // A new role has to be given a rank and thought about here; this fails until it is.
        assertThat(OrganizationRole.entries).containsExactly(OWNER, ADMIN, MEMBER)
    }
}
