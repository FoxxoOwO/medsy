package cz.medsy.di

import cz.medsy.data.settings.SettingsRepository
import cz.medsy.data.uv.OpenMeteoUvSource
import cz.medsy.data.uv.UvCache
import cz.medsy.data.uv.UvRepository
import cz.medsy.data.uv.UvSource
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object UvModule {

    @Provides
    @Singleton
    fun provideUvSource(): UvSource = OpenMeteoUvSource()

    @Provides
    @Singleton
    fun provideUvCache(settings: SettingsRepository): UvCache = settings

    @Provides
    @Singleton
    fun provideUvRepository(source: UvSource, cache: UvCache): UvRepository =
        UvRepository(source, cache)
}
