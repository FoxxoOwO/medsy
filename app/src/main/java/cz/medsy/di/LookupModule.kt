package cz.medsy.di

import cz.medsy.data.remote.TranslationApi
import cz.medsy.data.remote.TranslationService
import cz.medsy.data.remote.datasource.OpenFoodFactsRemoteDataSource
import cz.medsy.data.repository.MedicationLookupRepositoryImpl
import cz.medsy.data.repository.TranslationRepository
import cz.medsy.data.repository.TranslationRepositoryImpl
import cz.medsy.domain.repository.SupplementLookup
import cz.medsy.domain.repository.MedicationLookupRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Binds the waterfall so the scanner and the manual-entry form share one implementation. */
@Module
@InstallIn(SingletonComponent::class)
abstract class LookupModule {

    @Binds
    @Singleton
    abstract fun bindMedicationLookupRepository(
        impl: MedicationLookupRepositoryImpl,
    ): MedicationLookupRepository

    @Binds
    @Singleton
    abstract fun bindSupplementLookup(impl: OpenFoodFactsRemoteDataSource): SupplementLookup

    @Binds
    @Singleton
    abstract fun bindTranslationService(impl: TranslationApi): TranslationService

    @Binds
    @Singleton
    abstract fun bindTranslationRepository(impl: TranslationRepositoryImpl): TranslationRepository
}
