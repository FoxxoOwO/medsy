package cz.medsy.di

import android.content.Context
import androidx.work.WorkManager
import cz.medsy.data.settings.SettingsRepository
import cz.medsy.security.DatabaseKeyStore
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository =
        SettingsRepository(context)

    @Provides
    @Singleton
    fun provideDatabaseKeyStore(@ApplicationContext context: Context): DatabaseKeyStore =
        DatabaseKeyStore(context)

    @Provides
    @Singleton
    fun provideWorkManager(@ApplicationContext context: Context): WorkManager =
        WorkManager.getInstance(context)

    @Provides
    fun provideAlarmManager(@ApplicationContext context: Context): android.app.AlarmManager =
        context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager

    @Provides
    @Singleton
    fun provideHealthConnectWriter(
        writer: cz.medsy.health.PlatformHealthConnectWriter,
    ): cz.medsy.health.HealthConnectWriter = writer
}
