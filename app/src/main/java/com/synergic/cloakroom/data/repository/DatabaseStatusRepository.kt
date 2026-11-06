package com.synergic.cloakroom.data.repository

import com.synergic.cloakroom.data.Tenant
import com.synergic.cloakroom.data.db.dao.SettingDao
import com.synergic.cloakroom.data.db.dao.StatusDao
import com.synergic.cloakroom.data.db.dao.TableCounts
import com.synergic.cloakroom.data.db.dao.UserDao
import com.synergic.cloakroom.data.db.entity.SettingEntity
import com.synergic.cloakroom.data.db.entity.UserEntity
import com.synergic.cloakroom.data.seed.DemoSeeder
import com.synergic.cloakroom.domain.repository.AuthRepository
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Read-only view of the database for the developer status screen, plus the debug reset. */
@Singleton
class DatabaseStatusRepository @Inject constructor(
    private val statusDao: StatusDao,
    private val userDao: UserDao,
    private val settingDao: SettingDao,
    private val seeder: DemoSeeder,
    private val authRepository: AuthRepository,
) {
    fun observeCounts(): Flow<TableCounts> = statusDao.observeCounts()

    fun observeUsers(): Flow<List<UserEntity>> = userDao.observeAll(Tenant.CUSTOMER_ID)

    fun observeCustomerSettings(): Flow<List<SettingEntity>> =
        settingDao.observeScopedRows(Tenant.CUSTOMER_ID, null).map { rows -> rows.sortedBy { it.sortOrder } }

    /** Deletes all rows, seeds again and ends the local session (its user row is gone). */
    suspend fun resetDemoData() {
        seeder.resetDemoData()
        authRepository.clearLocalSession("Demo data was reset. Log in again.")
    }
}
