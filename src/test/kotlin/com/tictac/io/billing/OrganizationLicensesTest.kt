package com.tictac.io.billing

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource

/**
 * The licensing arithmetic on its own, with no database and no Stripe.
 *
 * Worth testing in isolation because it is the one place two rules live - "the first licence
 * is included free" and "an outstanding invitation is holding a licence" - and every capacity
 * refusal, reduction floor and Stripe quantity is derived from them. A sign error here would
 * be a billing error everywhere.
 */
@DisplayName("OrganizationLicenses: the licensing arithmetic")
class OrganizationLicensesTest {

    @ParameterizedTest(name = "{0} licences cost for {1}")
    @CsvSource("1, 0", "2, 1", "3, 2", "5, 4", "20, 19")
    fun `the first licence is included and every other one is billed`(licenses: Long, expected: Long) {
        assertThat(OrganizationLicenses.paidLicensesFor(licenses)).isEqualTo(expected)
    }

    @Test
    fun `paid licences never go negative`() {
        // Unreachable through the API - a CHECK constraint keeps license_count at 1 or more -
        // but the formula must not produce -1 if it ever were.
        assertThat(OrganizationLicenses.paidLicensesFor(0)).isZero()
    }

    @ParameterizedTest(name = "{0} licences, {1} members -> {2} vacant")
    @CsvSource("1, 1, 0", "5, 5, 0", "5, 4, 1", "5, 3, 2", "20, 17, 3")
    fun `a vacant licence is one with no member in it`(licenses: Long, members: Long, vacant: Long) {
        assertThat(OrganizationLicenses.vacantLicensesFor(licenses, members)).isEqualTo(vacant)
    }

    @Test
    fun `vacant goes negative only when licences were taken away underneath the members`() {
        // Reported, not corrected. Reducing the quantity in the Stripe dashboard below the
        // headcount does not turn anybody out.
        assertThat(OrganizationLicenses.vacantLicensesFor(licenseCount = 2, membersOccupying = 5)).isEqualTo(-3)
    }

    @ParameterizedTest(name = "{0} licences, {1} members, {2} invited -> {3} available")
    @CsvSource(
        // Nothing outstanding: available is just vacant.
        "5, 4, 0, 1",
        "5, 5, 0, 0",
        // The vacant licence is already promised to somebody who has not accepted yet.
        "5, 4, 1, 0",
        // Two invitations against two vacant licences: exactly used up.
        "5, 3, 2, 0",
        "6, 3, 2, 1",
    )
    fun `an outstanding invitation is holding a licence`(
        licenses: Long,
        members: Long,
        pending: Long,
        available: Long,
    ) {
        assertThat(OrganizationLicenses.licensesAvailableFor(licenses, members, pending)).isEqualTo(available)
    }

    @Test
    fun `having a licence available is exactly having a positive count of them`() {
        // Stated separately in the service - one branches, one is reported - so they have to
        // agree at every size.
        (1L..8L).forEach { licenses ->
            (0L..8L).forEach { members ->
                (0L..4L).forEach { pending ->
                    assertThat(OrganizationLicenses.hasLicenseAvailable(licenses, members, pending))
                        .describedAs("%d licences, %d members, %d invited", licenses, members, pending)
                        .isEqualTo(OrganizationLicenses.licensesAvailableFor(licenses, members, pending) > 0)
                }
            }
        }
    }

    @Test
    fun `a one-person organization on the free licence is full`() {
        // The included licence exists and its owner is in it, so bringing anybody else in
        // means acquiring one.
        assertThat(OrganizationLicenses.hasLicenseAvailable(licenseCount = 1, membersOccupying = 1, pendingInvitations = 0))
            .isFalse()
    }

    @ParameterizedTest(name = "{0} members -> floor of {1}")
    @CsvSource("0, 1", "1, 1", "4, 4", "17, 17")
    fun `the reduction floor is the members occupying licences, never below one`(members: Long, floor: Long) {
        assertThat(OrganizationLicenses.minimumLicensesFor(members)).isEqualTo(floor)
    }

    @Test
    fun `the floor ignores outstanding invitations`() {
        // Deliberate: blocking a reduction for up to a week because somebody was invited and
        // never answered would be a worse failure than the one it prevents. The invitation is
        // simply refused at acceptance if its licence is gone, and it survives.
        assertThat(OrganizationLicenses.minimumLicensesFor(3)).isEqualTo(3)
    }

    @Test
    fun `an organization sitting exactly at its floor has nothing vacant`() {
        val members = 4L
        val licenses = OrganizationLicenses.minimumLicensesFor(members)

        assertThat(OrganizationLicenses.vacantLicensesFor(licenses, members)).isZero()
        assertThat(OrganizationLicenses.hasLicenseAvailable(licenses, members, 0)).isFalse()
    }
}
