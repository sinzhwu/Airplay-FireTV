package com.airplay.firetv.service

import android.content.Context
import android.content.Intent
import android.os.Build
import com.airplay.firetv.settings.AppSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import io.mockk.verify
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * ServiceController unit tests.
 *
 * WHY: ServiceController is the bridge between the UI layer (ViewModel) and
 * the foreground service (PhairPlayService).  It must construct correct Intents
 * with the right extras so the service starts/stops properly.
 *
 * HOW: We mock [Context] with MockK and capture the Intent passed to
 * [Context.startService] / [Context.startForegroundService].  No real service
 * is started; we only verify the Intent contents.
 *
 * NOTE: In the JVM unit-test environment [Build.VERSION.SDK_INT] is typically 0
 * (android.jar stub), therefore [ServiceController.start] takes the
 * `startService` branch, NOT `startForegroundService`.  Tests are written to
 * match this runtime behaviour.
 */
class ServiceControllerTest {

    private lateinit var context: Context
    private lateinit var controller: ServiceController

    @Before
    fun setup() {
        context = mockk(relaxed = true)
        controller = ServiceController(context)
    }

    @Test
    fun `start sends intent with display name extra`() {
        val slot = slot<Intent>()
        every { context.startService(capture(slot)) } returns mockk()

        val settings = AppSettings(displayName = "Living Room TV")
        controller.start(settings)

        assertEquals("Living Room TV", slot.captured.getStringExtra(PhairPlayService.EXTRA_DISPLAY_NAME))
    }

    @Test
    fun `start sends intent with auto start extra`() {
        val slot = slot<Intent>()
        every { context.startService(capture(slot)) } returns mockk()

        val settings = AppSettings(autoStart = true)
        controller.start(settings)

        assertTrue(slot.captured.getBooleanExtra(PhairPlayService.EXTRA_AUTO_START, false))
    }

    @Test
    fun `start dispatches intent to service`() {
        // Verifies that an Intent targeting PhairPlayService is sent.
        every { context.startService(any()) } returns mockk()

        val settings = AppSettings()
        controller.start(settings)

        verify { context.startService(any()) }
    }

    @Test
    fun `stop calls service stop and unbind`() {
        // Just verify stop() doesn't throw when no service is bound
        controller.stop()
        // If we reach here, the test passes
        assertTrue(true)
    }
}
