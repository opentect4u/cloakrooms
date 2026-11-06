package com.synergic.cloakroom.domain.repository

import com.synergic.cloakroom.domain.auth.NotAuthorizedException
import com.synergic.cloakroom.domain.print.PrintDocument
import com.synergic.cloakroom.domain.report.DashboardData
import com.synergic.cloakroom.domain.report.ReportAccess
import com.synergic.cloakroom.domain.report.ReportData
import com.synergic.cloakroom.domain.report.ReportFilter
import com.synergic.cloakroom.domain.report.ReportShift
import com.synergic.cloakroom.domain.report.ReportUser
import com.synergic.cloakroom.domain.report.SyncExceptionRow
import kotlinx.coroutines.flow.Flow

/**
 * Dashboard numbers and reports. Rules (CLAUDE.md): normal users see only their own data and only
 * when REPORT_FLAG = Y; all-users reports and the total collection are for admins.
 * Breaking a rule throws [com.synergic.cloakroom.domain.auth.NotAuthorizedException].
 */
interface ReportRepository {
    /** Live numbers for the logged-in user; empty while logged out. */
    fun observeDashboard(): Flow<DashboardData>

    suspend fun reportAccess(): ReportAccess

    /** Checks REPORT_PWD. */
    suspend fun verifyReportPassword(password: String): Boolean

    /** Admin only: the users to filter by. */
    suspend fun reportUsers(): List<ReportUser>

    /** The shifts to filter by (active first, then removed ones). Anyone who may open reports. */
    suspend fun reportShifts(): List<ReportShift>

    /**
     * Normal users must pass their own md_user.id (or null, which then means their own);
     * admins may pass any user or null for all users.
     */
    suspend fun report(filter: ReportFilter): ReportData

    /** The same report laid out for printing or PDF. */
    suspend fun reportDocument(filter: ReportFilter): PrintDocument

    /** The server's sync exceptions not yet resolved (admins on the server); none on the phone. */
    suspend fun exceptions(): List<SyncExceptionRow> = emptyList()

    /** Marks one exception resolved on the server (admins). */
    suspend fun resolveException(id: Long): Unit = throw NotAuthorizedException("Only an admin can resolve exceptions.")
}
