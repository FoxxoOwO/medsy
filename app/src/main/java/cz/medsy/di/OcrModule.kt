package cz.medsy.di

import cz.medsy.data.lab.MlKitOcrRecognizer
import cz.medsy.data.lab.OcrRecognizer
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** The only production implementation; tests construct the extractor with a fake. */
@Module
@InstallIn(SingletonComponent::class)
abstract class OcrModule {

    @Binds
    @Singleton
    abstract fun bindOcrRecognizer(impl: MlKitOcrRecognizer): OcrRecognizer
}
