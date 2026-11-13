package cz.medsy.data.uv

import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.Duration
import java.time.Instant

/**
 * The rule the dashboard depends on: a fresh reading is served without a network call, an
 * old one is refetched, and a failed refetch returns nothing. Serving yesterday's UV as
 * today's would be a silent lie, so the stale value is deliberately dropped.
 */
class UvCacheTest {

    private val now: Instant = Instant.parse("2026-06-01T10:00:00Z")

    private class FakeSource(
        private val place: UvPlace? = UvPlace(50.08, 14.42, "Prague, Czechia"),
        private val uv: Double? = 7.4,
        private val failLocate: Boolean = false,
        private val failUv: Boolean = false,
    ) : UvSource {
        var locateCalls = 0
        var uvCalls = 0

        override suspend fun locate(city: String): UvPlace? {
            locateCalls++
            if (failLocate) error("offline")
            return place
        }

        override suspend fun uvIndex(place: UvPlace): Double? {
            uvCalls++
            if (failUv) error("offline")
            return uv
        }
    }

    private class FakeCache(var stored: UvReading? = null) : UvCache {
        override suspend fun read(): UvReading? = stored
        override suspend fun write(reading: UvReading) {
            stored = reading
        }
    }

    @Test
    fun `a fresh reading is served from the cache without the network`() = runTest {
        val cached = UvReading(6.5, now.minus(Duration.ofHours(1)), "Prague")
        val source = FakeSource()
        val repository = UvRepository(source, FakeCache(cached)) { now }

        val reading = repository.current("Prague")

        assertEquals(cached, reading)
        assertEquals(0, source.locateCalls)
        assertEquals(0, source.uvCalls)
    }

    @Test
    fun `an expired reading is refetched and replaces the cache`() = runTest {
        val cache = FakeCache(UvReading(4.0, now.minus(Duration.ofHours(4)), "Prague"))
        val source = FakeSource(uv = 7.4)
        val repository = UvRepository(source, cache) { now }

        val reading = repository.current("Prague")

        assertEquals(7.4, reading!!.uvIndex, 0.001)
        assertEquals(now, reading.fetchedAt)
        assertEquals(1, source.uvCalls)
        assertEquals(7.4, cache.stored!!.uvIndex, 0.001)
    }

    @Test
    fun `an expired cache with a dead network returns null, never the stale value`() = runTest {
        val cache = FakeCache(UvReading(9.9, now.minus(Duration.ofHours(12)), "Prague"))
        val repository = UvRepository(FakeSource(failUv = true), cache) { now }

        assertNull(repository.current("Prague"))
    }

    @Test
    fun `a failed geocode returns null`() = runTest {
        val repository = UvRepository(FakeSource(failLocate = true), FakeCache()) { now }

        assertNull(repository.current("Nowhere"))
    }

    @Test
    fun `no city means no request at all`() = runTest {
        val source = FakeSource()
        val repository = UvRepository(source, FakeCache()) { now }

        assertNull(repository.current("   "))
        assertNull(repository.current(null))
        assertEquals(0, source.locateCalls)
    }

    @Test
    fun `a reading of exactly three hours old is refetched`() = runTest {
        val cache = FakeCache(UvReading(4.0, now.minus(Duration.ofHours(3)), "Prague"))
        val source = FakeSource(uv = 8.0)
        val repository = UvRepository(source, cache) { now }

        assertEquals(8.0, repository.current("Prague")!!.uvIndex, 0.001)
        assertEquals(1, source.uvCalls)
    }
}
