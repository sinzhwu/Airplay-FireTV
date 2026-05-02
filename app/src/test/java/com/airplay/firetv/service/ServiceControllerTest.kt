package com.airplay.firetv.service

import android.content.Context
import android.content.Intent
import com.airplay.firetv.settings.AppSettings
import io.mockk.every
import io.mockk.mockk
import io.mockk.slot
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

/**
 * ServiceController unit tests.
 *
 * WHY: ServiceController is the bridge between the UI layer (ViewModel) and
 * the foreground service (PhairPlayService).  It must construct correct Intents
 * with the right action strings and extras so the service starts/stops properly.
 *
 * HOW: We mock [Context] with MockK and capture the Intent passed to
 * [Context.startService].  No real service is started; we only verify the
 * Intent contents.
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
    fun `start sends intent with correct action`() {
        val slot = slot<Intent>()
        every { context.startService(capture(slot)) } returns mockk()

        controller.start(AppSettings())

        assertEquals("com.airplay.firetv.ACTION_START", slot.captured.action)
    }

    @Test
    fun `stop sends intent with correct action`() {
        val slot = slot<Intent>()
        every { context.startService(capture(slot)) } returns mockk()

        controller.stop()

        assertEquals("com.airplay.firetv.ACTION_STOP", slot.captured.action)
    }

    @Test
    fun `start includes device name extra`() {
        val slot = slot<Intent>()
        every { context.startService(capture(slot)) } returns mockk()

        val settings = AppSettings(deviceName = "Living Room TV")
        controller.start(settings)

        val extras = slot.captured.extras
        assertNotNull(extras)
        assertEquals("Living Room TV", extras!!.getString("device_name"))
    }

    @Test
    fun `start includes password enabled extra`() {
        val slot = slot<Intent>()
        every { context.startService(capture(slot)) } returns mockk()

        val settings = AppSettings(password = "1234")
        controller.start(settings)

        val extras = slot.captured.extras
        assertNotNull(extras)
        assertTrue(extras!!.getBoolean("password_enabled"))
    }
}
