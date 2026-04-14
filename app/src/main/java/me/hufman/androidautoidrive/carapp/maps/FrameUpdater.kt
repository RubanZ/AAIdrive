package me.hufman.androidautoidrive.carapp.maps

import android.graphics.Bitmap
import android.media.ImageReader
import android.os.Handler
import android.util.Log
import de.bmw.idrive.BMWRemoting
import io.bimmergestalt.idriveconnectkit.rhmi.RHMIModel
import java.util.concurrent.Executor
import java.util.concurrent.Executors

interface FrameModeListener {
	fun onResume()
	fun onPause()
}

/**
 * Pulls frames from a [VirtualDisplayScreenCapture] and ships them to an RHMI model.
 *
 * Threading:
 *   - [start] is given the car's RHMI handler; [run] + [schedule] always post there, so
 *     the single-reader serialization on [VirtualDisplayScreenCapture]'s reused bitmaps is preserved.
 *   - JPEG compression and the Etch send are dispatched to [encoderExecutor], freeing the
 *     car handler thread for other RHMI events during the compress (~20-80 ms).
 *   - [encoderBusy] gates [run]: while a compress/send is in flight, incoming
 *     ImageReader callbacks are effectively dropped (the new frame stays in the ImageReader
 *     queue and [acquireLatestImage] will pick it up next time round).
 *
 * The default executor is a single-thread worker — crucial for correctness: it guarantees
 * that only one JPEG compress can read [VirtualDisplayScreenCapture]'s shared bitmap at a time
 * and that frames are sent to the car in the order they were captured. Tests inject a direct
 * executor so the whole pipeline runs synchronously on the test thread.
 */
class FrameUpdater(
		val display: VirtualDisplayScreenCapture,
		val modeListener: FrameModeListener?,
		private val encoderExecutor: Executor = defaultEncoderExecutor()
): Runnable {
	companion object {
		private fun defaultEncoderExecutor(): Executor =
				Executors.newSingleThreadExecutor { r ->
					Thread(r, "MapFrameEncoder").apply {
						isDaemon = true
						priority = Thread.NORM_PRIORITY
					}
				}
	}

	var destination: RHMIModel? = null
	var isRunning = true
	private var handler: Handler? = null

	// Written by handler thread (true) and encoder thread (false). Only the handler reads it.
	@Volatile
	private var encoderBusy: Boolean = false

	fun start(handler: Handler) {
		this.handler = handler
		Log.i(TAG, "Starting FrameUpdater thread with handler $handler")
		display.registerImageListener(ImageReader.OnImageAvailableListener // Called from the UI thread to say a new image is available
		{
			// let the car thread consume the image
			schedule()
		})
		schedule()  // check for a first image
	}

	override fun run() {
		if (!isRunning) return
		if (encoderBusy) {
			// encoder still shipping the previous frame — try again shortly.
			// We don't poll harder than ~50ms because compression typically finishes in one ImageReader interval.
			schedule(50)
			return
		}
		val bitmap = display.getFrame()
		if (bitmap == null) {
			// Either no new image yet, or the latest image hashes identical to the last one we sent.
			// Wait for the next OnImageAvailable; 1s is the safety-net fallback.
			schedule(1000)
			return
		}
		encoderBusy = true
		encoderExecutor.execute {
			try {
				val tStart = System.nanoTime()
				val imageData = display.compressBitmap(bitmap)
				val tEncoded = System.nanoTime()
				sendImageData(imageData)
				val tSent = System.nanoTime()
				Log.d(TAG, "frame size=${imageData.size}B encode=${(tEncoded - tStart) / 1_000_000}ms send=${(tSent - tEncoded) / 1_000_000}ms")
			} catch (t: Throwable) {
				Log.w(TAG, "Frame encode/send failed: $t")
			} finally {
				encoderBusy = false
				// Hand control back to the handler thread so the next run() happens with the right affinity.
				handler?.post { schedule() }
			}
		}
	}

	fun schedule(delayMs: Int = 0) {
		handler?.removeCallbacks(this)   // remove any previously-scheduled invocations
		handler?.postDelayed(this, delayMs.toLong())
	}

	fun shutDown() {
		isRunning = false
		display.registerImageListener(null)
		handler?.removeCallbacks(this)
		// Stop accepting new encode jobs but let the in-flight one finish cleanly.
		if (encoderExecutor is java.util.concurrent.ExecutorService) {
			encoderExecutor.shutdown()
		}
	}

	fun showWindow(width: Int, height: Int, destination: RHMIModel) {
		this.destination = destination
		Log.i(TAG, "Changing map mode to $width x $height")
		display.changeImageSize(width, height)
		modeListener?.onResume()
	}
	fun hideWindow(destination: RHMIModel) {
		if (this.destination == destination) {
			this.destination = null
			modeListener?.onPause()
		}
	}

	private fun sendImageData(imageData: ByteArray) {
		try {
			val destination = this.destination
			if (destination is RHMIModel.RaImageModel) {
				destination.value = imageData
			} else if (destination is RHMIModel.RaListModel) {
				val list = RHMIModel.RaListModel.RHMIListConcrete(1)
				list.addRow(arrayOf(BMWRemoting.RHMIResourceData(BMWRemoting.RHMIResourceType.IMAGEDATA, imageData)))
				destination.value = list
			}
		} catch (e: RuntimeException) {
		} catch (e: org.apache.etch.util.TimeoutException) {
			// don't crash if the phone is unplugged during a frame update
		}
	}

	// Kept only for source-compatibility with callers / tests that may reference it by name.
	@Suppress("unused")
	private fun sendImage(bitmap: Bitmap) {
		sendImageData(display.compressBitmap(bitmap))
	}
}
