package com.synergic.cloakroom.di

import android.content.Context
import com.synergic.cloakroom.data.db.AppDatabase
import com.synergic.cloakroom.data.db.dao.CustomerDao
import com.synergic.cloakroom.data.db.dao.HeaderFooterDao
import com.synergic.cloakroom.data.db.dao.IdProofDao
import com.synergic.cloakroom.data.db.dao.LuggageDao
import com.synergic.cloakroom.data.db.dao.ReceiptDao
import com.synergic.cloakroom.data.db.dao.SettingDao
import com.synergic.cloakroom.data.db.dao.ShiftDao
import com.synergic.cloakroom.data.db.dao.StatusDao
import com.synergic.cloakroom.data.db.dao.UserCounterDao
import com.synergic.cloakroom.data.db.dao.UserDao
import com.synergic.cloakroom.data.device.AndroidDeviceInfo
import com.synergic.cloakroom.data.print.PosPrinter
import com.synergic.cloakroom.data.repository.AuthRepositoryImpl
import com.synergic.cloakroom.data.repository.BillRepositoryImpl
import com.synergic.cloakroom.data.repository.LuggageRepositoryImpl
import com.synergic.cloakroom.data.repository.MasterRepositoryImpl
import com.synergic.cloakroom.data.repository.ReportRepositoryRouter
import com.synergic.cloakroom.data.repository.SettingsRepositoryImpl
import com.synergic.cloakroom.data.session.SessionManager
import com.synergic.cloakroom.data.session.SessionStore
import com.synergic.cloakroom.data.session.SecureSessionStore
import com.synergic.cloakroom.data.session.TokenStore
import com.synergic.cloakroom.data.remote.AndroidConnectivity
import com.synergic.cloakroom.data.remote.HttpSyncApi
import com.synergic.cloakroom.data.remote.SyncApi
import com.synergic.cloakroom.data.sync.PrefsSyncCursorStore
import com.synergic.cloakroom.data.sync.SyncCursorStore
import com.synergic.cloakroom.data.sync.SyncManager
import com.synergic.cloakroom.domain.repository.SyncRepository
import com.synergic.cloakroom.data.remote.ApiConfig
import com.synergic.cloakroom.data.remote.AuthApi
import com.synergic.cloakroom.data.remote.HttpAuthApi
import com.synergic.cloakroom.data.repository.CustomerSettingsRepositoryImpl
import com.synergic.cloakroom.data.repository.SuperAdminRepositoryImpl
import com.synergic.cloakroom.domain.remote.Connectivity
import com.synergic.cloakroom.domain.repository.CustomerSettingsRepository
import com.synergic.cloakroom.domain.repository.SuperAdminRepository
import com.synergic.cloakroom.BuildConfig
import com.synergic.cloakroom.domain.auth.SessionProvider
import com.synergic.cloakroom.domain.device.DeviceInfo
import com.synergic.cloakroom.domain.print.Printer
import com.synergic.cloakroom.domain.repository.AuthRepository
import com.synergic.cloakroom.domain.repository.BillRepository
import com.synergic.cloakroom.domain.repository.LuggageRepository
import com.synergic.cloakroom.domain.repository.MasterRepository
import com.synergic.cloakroom.domain.repository.ReportRepository
import com.synergic.cloakroom.domain.repository.SettingsRepository
import com.synergic.cloakroom.domain.time.TimeSource
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import java.time.Instant
import javax.inject.Qualifier
import javax.inject.Singleton

/** Scope for work that must outlive a screen, such as first-launch seeding. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase = AppDatabase.build(context)

    @Provides fun customerDao(db: AppDatabase): CustomerDao = db.customerDao()
    @Provides fun userDao(db: AppDatabase): UserDao = db.userDao()
    @Provides fun idProofDao(db: AppDatabase): IdProofDao = db.idProofDao()
    @Provides fun headerFooterDao(db: AppDatabase): HeaderFooterDao = db.headerFooterDao()
    @Provides fun shiftDao(db: AppDatabase): ShiftDao = db.shiftDao()
    @Provides fun settingDao(db: AppDatabase): SettingDao = db.settingDao()
    @Provides fun luggageDao(db: AppDatabase): LuggageDao = db.luggageDao()
    @Provides fun receiptDao(db: AppDatabase): ReceiptDao = db.receiptDao()
    @Provides fun userCounterDao(db: AppDatabase): UserCounterDao = db.userCounterDao()
    @Provides fun statusDao(db: AppDatabase): StatusDao = db.statusDao()

    @Provides
    @Singleton
    fun provideTimeSource(): TimeSource = TimeSource { Instant.now() }

    /** API_GUIDE.md §0: base URL per build type (app/build.gradle.kts). */
    @Provides
    @Singleton
    fun provideApiConfig(): ApiConfig = ApiConfig(BuildConfig.API_BASE_URL)

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
}

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    abstract fun bindSettingsRepository(impl: SettingsRepositoryImpl): SettingsRepository

    @Binds
    abstract fun bindAuthRepository(impl: AuthRepositoryImpl): AuthRepository

    @Binds
    abstract fun bindSessionProvider(impl: SessionManager): SessionProvider

    @Binds
    abstract fun bindSessionStore(impl: SecureSessionStore): SessionStore

    @Binds
    abstract fun bindTokenStore(impl: SecureSessionStore): TokenStore

    @Binds
    abstract fun bindConnectivity(impl: AndroidConnectivity): Connectivity

    @Binds
    abstract fun bindSyncRepository(impl: SyncManager): SyncRepository

    @Binds
    abstract fun bindSyncApi(impl: HttpSyncApi): SyncApi

    @Binds
    abstract fun bindSyncCursorStore(impl: PrefsSyncCursorStore): SyncCursorStore

    @Binds
    abstract fun bindAuthApi(impl: HttpAuthApi): AuthApi

    @Binds
    abstract fun bindSuperAdminRepository(impl: SuperAdminRepositoryImpl): SuperAdminRepository

    @Binds
    abstract fun bindCustomerSettingsRepository(impl: CustomerSettingsRepositoryImpl): CustomerSettingsRepository

    @Binds
    abstract fun bindDeviceInfo(impl: AndroidDeviceInfo): DeviceInfo

    @Binds
    abstract fun bindLuggageRepository(impl: LuggageRepositoryImpl): LuggageRepository

    @Binds
    abstract fun bindBillRepository(impl: BillRepositoryImpl): BillRepository

    @Binds
    abstract fun bindReportRepository(impl: ReportRepositoryRouter): ReportRepository

    @Binds
    abstract fun bindMasterRepository(impl: MasterRepositoryImpl): MasterRepository

    /** The GC039 / GC039ES printer, chosen by the PRINTER_TYPE setting (falls back to preview). */
    @Binds
    abstract fun bindPrinter(impl: PosPrinter): Printer
}
