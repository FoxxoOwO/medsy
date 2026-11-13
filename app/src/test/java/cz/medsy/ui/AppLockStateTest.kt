package cz.medsy.ui

import androidx.test.core.app.ApplicationProvider
import cz.medsy.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * The lock has to survive leaving the app. Previously `unlocked` was only ever set to
 * true, so once a session was open it stayed open forever, and enabling the lock in
 * settings did not take effect until the next cold start.
 */
@RunWith(RobolectricTestRunner::class)
class AppLockStateTest {

    private lateinit var settingsRepository: SettingsRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        settingsRepository = SettingsRepository(
            ApplicationProvider.getApplicationContext<android.app.Application>(),
        )
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun viewModel() = AppViewModel(settingsRepository)

    @Test
    fun `lock is open when no biometric lock is configured`() = runBlocking {
        settingsRepository.setBiometricLock(false)
        val vm = viewModel()

        assertTrue(vm.unlocked.value)
    }

    @Test
    fun `lock starts closed when biometric lock is configured`() = runBlocking {
        settingsRepository.setBiometricLock(true)
        val vm = viewModel()

        assertFalse("a restart must require authentication", vm.unlocked.value)
    }

    @Test
    fun `a successful authentication opens the lock`() = runBlocking {
        settingsRepository.setBiometricLock(true)
        val vm = viewModel()
        assertFalse(vm.unlocked.value)

        vm.onAuthenticated()

        assertTrue(vm.unlocked.value)
    }

    @Test
    fun `leaving the app closes the lock again`() = runBlocking {
        settingsRepository.setBiometricLock(true)
        val vm = viewModel()
        vm.onAuthenticated()
        assertTrue(vm.unlocked.value)

        vm.lock()

        assertFalse("backgrounding must re-lock", vm.unlocked.value)
    }

    @Test
    fun `enabling the lock mid session closes it immediately`() = runBlocking {
        settingsRepository.setBiometricLock(false)
        val vm = viewModel()
        assertTrue(vm.unlocked.value)

        settingsRepository.setBiometricLock(true)

        assertFalse("turning the lock on must not leave the session open", vm.unlocked.value)
    }

    @Test
    fun `disabling the lock opens it`() = runBlocking {
        settingsRepository.setBiometricLock(true)
        val vm = viewModel()
        assertFalse(vm.unlocked.value)

        settingsRepository.setBiometricLock(false)

        assertTrue(vm.unlocked.value)
    }
}
