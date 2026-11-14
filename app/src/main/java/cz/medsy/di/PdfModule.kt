package cz.medsy.di

import cz.medsy.data.lab.LabPdfExtractor
import cz.medsy.data.lab.PdfTextExtractor
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * The one PDF text layer implementation, bound to the interface so the prescription importer
 * can be tested with a fake file instead of a real PDF.
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class PdfModule {

    @Binds
    abstract fun bindPdfTextExtractor(impl: LabPdfExtractor): PdfTextExtractor
}
