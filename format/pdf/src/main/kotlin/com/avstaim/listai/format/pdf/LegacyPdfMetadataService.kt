package com.avstaim.listai.format.pdf

import android.app.Service
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.Messenger
import android.os.ParcelFileDescriptor
import android.os.Process
import com.avstaim.listai.format.fb2.BookParseException
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

private const val READ_PAGES = 1
private const val SOURCE = "source"
private const val SIZES = "sizes"
private const val ERROR = "error"
private const val LIMIT = "limit"
private const val MAX_IPC_PAGES = 10_000
private const val TIMEOUT_SECONDS = 60L

/**
 * API 26/27's PDFium last-error state survives failed opens and poisons subsequent imports.
 * Validation therefore uses a fresh isolated process with only a read-only descriptor, then exits.
 * API 28 removed the erroneous init check and uses the regular worker path without IPC.
 * https://android.googlesource.com/platform/frameworks/base/+/android-8.0.0_r1/core/jni/android/graphics/pdf/PdfUtils.cpp
 * https://android.googlesource.com/platform/frameworks/base/+/android-9.0.0_r1/core/jni/android/graphics/pdf/PdfUtils.cpp
 */
class LegacyPdfMetadataService : Service() {
    private lateinit var worker: HandlerThread
    private lateinit var messenger: Messenger

    override fun onCreate() {
        super.onCreate()
        worker = HandlerThread("Listai.pdf-metadata").apply { start() }
        messenger = Messenger(Handler(worker.looper) { request ->
            if (request.what != READ_PAGES) return@Handler false
            val reply = Message.obtain(null, READ_PAGES)
            reply.data = try {
                @Suppress("DEPRECATION")
                val descriptor = requireNotNull(request.data.getParcelable<ParcelFileDescriptor>(SOURCE))
                val maxPages = request.arg1.coerceIn(1, MAX_IPC_PAGES)
                Bundle().apply { putIntArray(SIZES, readPdfPageSizes(descriptor, maxPages)) }
            } catch (_: SecurityException) {
                Bundle().apply { putString(ERROR, "encrypted") }
            } catch (error: BookParseException.ResourceLimitExceeded) {
                Bundle().apply { putString(ERROR, "limit"); putLong(LIMIT, error.limit) }
            } catch (_: Exception) {
                Bundle().apply { putString(ERROR, "malformed") }
            }
            try { request.replyTo?.send(reply) } finally {
                // This service has android:isolatedProcess=true; the application stays alive.
                Process.killProcess(Process.myPid())
            }
            true
        })
    }

    override fun onBind(intent: Intent): IBinder = messenger.binder

    override fun onUnbind(intent: Intent): Boolean {
        // Cancellation/timeout can stop native validation even while its worker is occupied.
        Process.killProcess(Process.myPid())
        return false
    }
}

/** Blocking only inside the parser's interruptible IO call; cancellation always unbinds. */
internal fun readLegacyPdfPageSizes(context: Context, file: File, maxPages: Int): IntArray {
    val complete = CountDownLatch(1)
    val response = AtomicReference<Bundle?>()
    val main = Handler(Looper.getMainLooper())
    var disconnected = false // Accessed only on main.
    val reply = Messenger(Handler(Looper.getMainLooper()) { message ->
        response.set(message.data)
        if (disconnected) complete.countDown()
        true
    })
    val descriptor = ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    val connection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            try {
                Messenger(service).send(Message.obtain(null, READ_PAGES).apply {
                    arg1 = maxPages
                    data = Bundle().apply { putParcelable(SOURCE, descriptor) }
                    replyTo = reply
                })
            } catch (_: Exception) { complete.countDown() }
        }

        override fun onServiceDisconnected(name: ComponentName) {
            disconnected = true
            if (response.get() != null) complete.countDown()
            // A native crash may deliver no response. Allow an already queued reply to arrive.
            else main.postDelayed({ complete.countDown() }, 250L)
        }

        override fun onBindingDied(name: ComponentName) = onServiceDisconnected(name)
        override fun onNullBinding(name: ComponentName) { complete.countDown() }
    }
    var bound = false
    try {
        bound = context.bindService(Intent(context, LegacyPdfMetadataService::class.java), connection,
            Context.BIND_AUTO_CREATE)
        if (!bound || !complete.await(TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw IOException("PDF validation service unavailable or timed out")
        }
        val data = response.get() ?: throw BookParseException.MalformedDocument()
        when (data.getString(ERROR)) {
            "encrypted" -> throw BookParseException.EncryptedDocument()
            "limit" -> throw BookParseException.ResourceLimitExceeded("PDF pages", data.getLong(LIMIT))
            "malformed" -> throw BookParseException.MalformedDocument()
        }
        val sizes = data.getIntArray(SIZES) ?: throw BookParseException.MalformedDocument()
        if (sizes.isEmpty() || sizes.size % 2 != 0 || sizes.size > MAX_IPC_PAGES * 2 || sizes.any { it <= 0 }) {
            throw BookParseException.MalformedDocument()
        }
        return sizes
    } finally {
        if (bound) context.unbindService(connection)
        descriptor.close()
    }
}
