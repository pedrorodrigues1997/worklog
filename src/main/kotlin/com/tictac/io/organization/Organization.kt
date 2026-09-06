package com.tictac.io.organization

import com.tictac.io.billing.OrganizationLicenses
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant
import java.util.UUID

/**
 * A customer organization. Owns the business data; users only reach it through a
 * membership.
 *
 * A plain class rather than a data class, matching the other entities: a generated
 * `equals`/`hashCode` over all columns is wrong for something with a generated id.
 *
 * There is deliberately no `isDeleted()`-style helper. Hibernate resolves property access
 * through getters here, so a no-argument `isX()` on an entity is indistinguishable from a
 * mapped boolean property and would break `ddl-auto=validate` at startup. Callers compare
 * [deletedAt] directly.
 */
@Entity
@Table(name = "organizations")
class Organization(
    @Column(name = "organization_name", nullable = false, length = MAX_NAME_LENGTH)
    var name: String,
) {
    /** Assigned by Hibernate on persist; null until then. Never set this by hand. */
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "organization_id", nullable = false, updatable = false)
    var id: UUID? = null

    /**
     * Licenses this organization holds - the count it pays for, not the count it is using.
     *
     * Starts at [OrganizationLicenses.INCLUDED_LICENSES]: every organization gets one free,
     * which is what lets a one-person company exist with no Stripe relationship at all.
     *
     * Deliberately **not** derived from the member count. A member occupies a license; removing
     * them vacates it and leaves this number alone, so the organization keeps what it bought
     * until an administrator gives it up. Changing it is a billing operation and belongs to
     * [com.tictac.io.billing.OrganizationLicenseService] - nothing else may write it.
     */
    @Column(name = "license_count", nullable = false)
    var licenseCount: Int = OrganizationLicenses.INCLUDED_LICENSES.toInt()

    @Column(name = "created_at", nullable = false, updatable = false)
    var createdAt: Instant = Instant.now()

    /** Soft-delete marker. Null means the organization is active. */
    @Column(name = "deleted_at")
    var deletedAt: Instant? = null

    override fun toString(): String = "Organization(id=$id, name=$name)"

    companion object {
        const val MAX_NAME_LENGTH = 200
    }
}
