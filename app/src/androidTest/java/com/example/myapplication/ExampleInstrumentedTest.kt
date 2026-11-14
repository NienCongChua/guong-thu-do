package com.example.myapplication

import android.Manifest
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ExampleInstrumentedTest {
    @Test
    fun apkDoesNotRequestMicrophonePermission() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val permissions = context.packageManager.getPackageInfo(context.packageName,
            PackageManager.GET_PERMISSIONS).requestedPermissions.orEmpty().toSet()
        assertTrue(Manifest.permission.CAMERA in permissions)
        assertTrue(Manifest.permission.INTERNET in permissions)
        assertFalse(Manifest.permission.RECORD_AUDIO in permissions)
    }

    @Test
    fun authenticateFromPhoneToActualDemoBackend(): Unit = runBlocking {
        val args = InstrumentationRegistry.getArguments()
        val url = args.getString("backendUrl")
        val password = args.getString("demoPassword")
        // Opt-in: standard runs do not require a local server or embed a credential.
        assumeTrue("Pass backendUrl and demoPassword instrumentation arguments",
            url != null && password != null)
        val api = BackendApi(url!!)
        try {
            api.login(password!!)
            assertTrue(api.bearer.isNotBlank())
            val caps = api.capabilities()
            assertTrue(caps.maxSeconds in 1..600)
            args.getString("expectedDecart")?.let { assertEquals(it.toBoolean(), caps.decart) }
            args.getString("expectedOpenAI")?.let { assertEquals(it.toBoolean(), caps.openai) }
        } finally {
            api.close()
        }
    }
}
