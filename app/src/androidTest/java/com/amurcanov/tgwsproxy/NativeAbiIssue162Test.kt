package com.amurcanov.tgwsproxy

import android.content.Intent
import android.os.Process
import android.os.ParcelFileDescriptor
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.DataInputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Real JNA/CGO calls and controlled local payload, never Telegram traffic evidence. */
@RunWith(AndroidJUnit4::class)
class NativeAbiIssue162Test {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context = instrumentation.targetContext

    private fun waitUntil(label: String, predicate: () -> Boolean) {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15)
        while (!predicate()) {
            check(System.nanoTime() < deadline) { "Timed out: $label" }
            Thread.sleep(100)
        }
    }

    private fun freePort() = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }

    @Test
    fun nativeLibraryListenerRoundTripAndServiceLifecycle() {
        check(context.packageName.endsWith(".abismoke")) { "Requires isolated debug package" }
        val expected = InstrumentationRegistry.getArguments().getString("expectedBits")!!.toInt()
        assertEquals("Actual Android process bitness", expected == 64, Process.is64Bit())
        assertEquals(expected / 8, com.sun.jna.Native.POINTER_SIZE)
        Log.i("Issue162", "NATIVE_BITS=$expected JNA_POINTER=${com.sun.jna.Native.POINTER_SIZE}")
        val library = java.io.File(context.applicationInfo.nativeLibraryDir, "libtgwsproxy.so")
        if (library.isFile) {
            val header = library.inputStream().use { it.readBytes().take(6) }
            assertEquals(if (expected == 32) 1.toByte() else 2.toByte(), header[4])
        }
        // Exercise dependency crypto and long/pointer CGO signatures, without logging keys.
        assertNotNull(NativeProxy.getStats())
        assertTrue(NativeProxy.generateWireGuardKeyPair().isSuccess)
        assertFalse(NativeProxy.probeAwgWarpConfig("/nonexistent/issue162.conf", "127.0.0.1:9", 250L).ok)
        val port = freePort()
        val config = "@route_direct=0|@route_worker=0|@route_cf_proxy=0|@route_tcp_fallback=1"
        try {
            repeat(2) {
                assertEquals(0, NativeProxy.startProxy("127.0.0.1", port, config, 0))
                assertNotNull(NativeProxy.getProxyStatus())
                roundTrip(port)
                assertEquals(0, NativeProxy.stopProxy())
                waitUntil("listener closes") {
                    runCatching { Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 200) } }.isFailure
                }
            }
        } finally { NativeProxy.stopProxy() }

        val launch = context.packageManager.getLaunchIntentForPackage(context.packageName)!!
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        // MIUI blocks Activity launches from a background instrumentation process.
        // The shell can foreground our isolated Activity without changing device settings.
        val shell = "am start -n ${context.packageName}/com.amurcanov.tgwsproxy.MainActivity"
        ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(shell))
            .bufferedReader().use { check(!it.readText().contains("Error:")) }
        var activity: android.app.Activity? = null
        waitUntil("Activity resumes") {
            instrumentation.runOnMainSync {
                activity = ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull { it is MainActivity }
            }
            activity != null
        }
        try {
            val servicePort = freePort()
            context.startForegroundService(Intent(context, ProxyService::class.java)
                .setAction(ProxyService.ACTION_START).putExtra(ProxyService.EXTRA_PORT, servicePort))
            waitUntil("foreground service starts") { ProxyService.isRunning.value }
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", servicePort), 2000) }
            instrumentation.runOnMainSync { activity!!.moveTaskToBack(true) }
            Thread.sleep(1000)
            assertTrue(ProxyService.isRunning.value)
            ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(shell))
                .bufferedReader().use { check(!it.readText().contains("Error:")) }
            context.startService(Intent(context, ProxyService::class.java).setAction(ProxyService.ACTION_STOP))
            waitUntil("foreground service stops") { !ProxyService.isRunning.value }
        } finally {
            context.startService(Intent(context, ProxyService::class.java).setAction(ProxyService.ACTION_STOP))
            instrumentation.runOnMainSync { activity!!.finish() }
        }
        Log.i("Issue162", "PASS native=$expected listener restart SOCKS_TCP_echo activity foreground background stop")
    }

    private fun roundTrip(proxyPort: Int) {
        val executor = Executors.newSingleThreadExecutor()
        ServerSocket(0, 1, InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val payload = ByteArray(8192) { (it % 251).toByte() }
            val echo = executor.submit {
                server.accept().use { peer ->
                    peer.soTimeout = 5000
                    val data = ByteArray(payload.size)
                    DataInputStream(peer.getInputStream()).readFully(data)
                    peer.getOutputStream().write(data)
                }
            }
            try {
                Socket("127.0.0.1", proxyPort).use { socket ->
                    socket.soTimeout = 5000
                    val output = socket.getOutputStream()
                    val input = DataInputStream(socket.getInputStream())
                    output.write(byteArrayOf(5, 1, 0))
                    assertEquals(5, input.readUnsignedByte())
                    assertEquals(0, input.readUnsignedByte())
                    output.write(byteArrayOf(5, 1, 0, 1, 127, 0, 0, 1,
                        (server.localPort shr 8).toByte(), server.localPort.toByte()))
                    val reply = ByteArray(10)
                    input.readFully(reply)
                    assertEquals(0, reply[1].toInt())
                    output.write(payload)
                    val received = ByteArray(payload.size)
                    input.readFully(received)
                    assertArrayEquals(payload, received)
                }
                echo.get(8, TimeUnit.SECONDS)
            } finally { executor.shutdownNow() }
        }
    }
}
