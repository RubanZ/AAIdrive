package me.hufman.androidautoidrive.carapp.maps

import android.content.Context
import android.graphics.*
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

interface ScreenCaptureConfig {
	val maxWidth: Int
	val maxHeight: Int
	val compressFormat: Bitmap.CompressFormat
	val compressQuality: Int
}

data class StaticScreenCaptureConfig(override val maxWidth: Int,
                                     override val maxHeight: Int,
                                     override var compressFormat: Bitmap.CompressFormat = Bitmap.CompressFormat.JPEG,
                                     override var compressQuality: Int = 65  //quality 65 is fine, and you get readable small texts, below that it was sometimes hard to read
): ScreenCaptureConfig

/**
 * Generates images from an ImageReader, handily resized and compressed to JPG.
 * VirtualDisplayScreenCapture.createVirtualDisplay can take this imageCapture and render to it.
 *
 * Two resize modes:
 *  1. **CPU resize** (legacy / unit tests). [changeImageSize] only updates `resizedBitmap` /
 *     `sourceRect`; [convertToBitmap] runs a Canvas.drawBitmap bilinear downscale on the CPU.
 *     Stacks a CPU blur on top of whatever the producer rendered, which then spends extra
 *     JPEG bits on the high-frequency edges.
 *  2. **Native resize** (production). If a [VirtualDisplay] is attached via
 *     [attachVirtualDisplay], [changeImageSize] recreates the backing [ImageReader] at the
 *     target size, resizes the VirtualDisplay itself, and rebinds its Surface. The map
 *     producer (Mapbox / GMaps / …) then renders *directly* at widget resolution — no
 *     intermediate bilinear pass. `sourceRect == resizedRect` is always true in this mode,
 *     so [convertToBitmap] skips the resize step entirely.
 */
class VirtualDisplayScreenCapture(initialCapture: ImageReader, val bitmapConfig: Bitmap.Config, val screenCaptureConfig: ScreenCaptureConfig) {
	companion object {
		private const val INITIAL_JPEG_BUFFER = 128 * 1024   // typical map-tile JPEG fits in ~64 KiB; pre-size to avoid grow-reallocations
		private const val EMPTY_FRAME_HASH = 0L
		private const val HASH_SAMPLE_COUNT = 64             // 64 × 8 bytes = 512 B of entropy per frame; sub-millisecond cost
		private const val FNV_OFFSET_BASIS = -0x340d631b7bdddcdbL
		private const val FNV_PRIME = 0x100000001b3L

		fun build(config: ScreenCaptureConfig): VirtualDisplayScreenCapture {
			return VirtualDisplayScreenCapture(
					ImageReader.newInstance(config.maxWidth, config.maxHeight, PixelFormat.RGBA_8888, 2),
					Bitmap.Config.ARGB_8888,
					config)
		}

		fun createVirtualDisplay(context: Context, imageCapture: ImageReader, dpi:Int = 100, name: String = "IDriveVirtualDisplay"): VirtualDisplay {
			val displayManager = context.getSystemService(DisplayManager::class.java)
			return displayManager.createVirtualDisplay(name,
					imageCapture.width, imageCapture.height, dpi,
					imageCapture.surface, DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY,
					null, Handler(Looper.getMainLooper()))
		}

	}

	/** Prepares an ImageReader, and sends JPG-compressed images to a callback */
	// Mutable so it can be reallocated in native-resize mode. Callers that already hold a
	// reference to the old instance (e.g. anything that was mid-flight when a resize happened)
	// will keep reading from the old one until they complete and release it — that's fine,
	// we only close the old instance AFTER unbinding it from the VirtualDisplay.
	var imageCapture: ImageReader = initialCapture
		private set
	private var origRect = Rect(0, 0, imageCapture.width, imageCapture.height)    // the full size of the main map
	private var sourceRect = Rect(0, 0, imageCapture.width, imageCapture.height)    // the capture region from the main map
	private var bitmap = Bitmap.createBitmap(imageCapture.width, imageCapture.height, bitmapConfig)
	private val resizeFilter = Paint().apply { this.isFilterBitmap = false }
	private var resizedBitmap = Bitmap.createBitmap(imageCapture.width, imageCapture.height, bitmapConfig)
	private var resizedCanvas = Canvas(resizedBitmap)
	private var resizedRect = Rect(0, 0, resizedBitmap.width, resizedBitmap.height) // draw to the full region of the resize canvas
	private val outputFile = ByteArrayOutputStream(INITIAL_JPEG_BUFFER)

	// Change detection: sparse FNV-1a hash of the raw image plane.
	// When two consecutive frames hash to the same value we skip the JPEG encode
	// and the Etch round-trip — saves the bulk of CPU time when the map is idle.
	private var lastFrameHash: Long = EMPTY_FRAME_HASH

	// Native-resize mode state. Present only after attachVirtualDisplay() has been called.
	private var virtualDisplay: VirtualDisplay? = null
	private var virtualDisplayDensity: Int = 100
	// Cached listener so we can rebind it after recreating [imageCapture].
	private var currentImageListener: ImageReader.OnImageAvailableListener? = null
	private val mainHandler = Handler(Looper.getMainLooper())


	/**
	 * Opt into native-resize mode. After this call, every [changeImageSize] will recreate
	 * the backing [ImageReader] at the requested size and rebind `vd` to the new Surface,
	 * so the producer renders directly at widget resolution.
	 *
	 * Call this once, right after creating the VirtualDisplay from this instance's
	 * `imageCapture`. Safe to call on any thread; [changeImageSize] is the only operation
	 * that mutates the display and is always called from the car handler.
	 */
	fun attachVirtualDisplay(vd: VirtualDisplay, density: Int) {
		synchronized(this) {
			virtualDisplay = vd
			virtualDisplayDensity = density
		}
	}

	fun registerImageListener(listener: ImageReader.OnImageAvailableListener?) {
		currentImageListener = listener
		this.imageCapture.setOnImageAvailableListener(listener, mainHandler)
	}

	fun changeImageSize(width: Int, height: Int) {
		synchronized(this) {
			val vd = virtualDisplay
			if (vd != null) {
				// Native-resize path: recreate ImageReader at the target size, resize the
				// VirtualDisplay, rebind its Surface, and throw away the CPU-resize machinery
				// (sourceRect == resizedRect so convertToBitmap skips the drawBitmap step).
				Log.i(TAG, "Native-resizing map pipeline to ${width}x${height}")

				val oldCapture = imageCapture
				val newCapture = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

				// Rebind the display first so in-flight producer frames go to the new buffer.
				vd.resize(width, height, virtualDisplayDensity)
				vd.surface = newCapture.surface

				// Swap the field and re-register the listener on the new reader before we drop
				// the old one — otherwise a frame could land during the gap and be lost forever.
				imageCapture = newCapture
				currentImageListener?.let { newCapture.setOnImageAvailableListener(it, mainHandler) }

				// Reset CPU-resize state to identity rectangles.
				origRect = Rect(0, 0, width, height)
				sourceRect = Rect(0, 0, width, height)
				resizedRect = Rect(0, 0, width, height)
				bitmap = Bitmap.createBitmap(width, height, bitmapConfig)
				resizedBitmap = bitmap
				resizedCanvas = Canvas(resizedBitmap)
				lastFrameHash = EMPTY_FRAME_HASH

				// Tear down the old reader on the main looper after a short delay so any in-flight
				// acquireLatestImage / onImageAvailable work on it finishes gracefully.
				oldCapture.setOnImageAvailableListener(null, null)
				mainHandler.postDelayed({
					try {
						oldCapture.close()
					} catch (t: Throwable) {
						Log.w(TAG, "Old ImageReader close threw: $t")
					}
				}, 100)
			} else {
				// Legacy CPU-resize path. `bitmap` stays at the (unchanged) imageCapture size and
				// convertToBitmap does a Canvas.drawBitmap downscale into `resizedBitmap` each frame.
				resizedBitmap = Bitmap.createBitmap(width, height, bitmapConfig)
				resizedCanvas = Canvas(resizedBitmap)
				resizedRect = Rect(0, 0, resizedBitmap.width, resizedBitmap.height)
				sourceRect = findInnerRect(origRect, resizedRect)
				lastFrameHash = EMPTY_FRAME_HASH
				Log.i(TAG, "Preparing CPU resize pipeline of $sourceRect to $resizedRect")
			}
		}
	}

	private fun findInnerRect(fullRect: Rect, smallRect: Rect): Rect {
		/** Given a destination smallRect,
		 * find the biggest rect inside fullRect that matches the aspect ratio
		 */
		val aspectRatio: Float = 1.0f * smallRect.width() / smallRect.height()
		// try for max width
		var width = fullRect.width()
		var height = (width / aspectRatio).toInt()
		if (height > fullRect.height()) {
			// try for max height
			height = fullRect.height()
			width = (height * aspectRatio).toInt()
		}
		val left = fullRect.width() / 2 - width / 2
		val top = fullRect.height() / 2 - height / 2
		return Rect(left, top, left+width, top+height)
	}

	private fun convertToBitmap(image: Image): Bitmap {
		// read from the image store to a Bitmap object
		val planes = image.planes
		val buffer = planes[0].buffer
		val padding = planes[0].rowStride - planes[0].pixelStride * image.width
		val width = image.width + padding / planes[0].pixelStride
		if (bitmap.width != width) {
			Log.i(TAG, "Setting capture bitmap to ${width}x${imageCapture.height}")
			bitmap = Bitmap.createBitmap(width, imageCapture.height, bitmapConfig)
			// If the native-resize path points `resizedBitmap` at the same instance as `bitmap`,
			// keep them aligned after a reallocation.
			if (virtualDisplay != null) {
				resizedBitmap = bitmap
				resizedCanvas = Canvas(resizedBitmap)
			}
		}
		bitmap.copyPixelsFromBuffer(buffer)

		// resize the image
		var outputBitmap: Bitmap = bitmap
		synchronized(this) {
			if (sourceRect != resizedRect) {
				// CPU-resize path only — in native-resize mode these rects are always equal.
				resizedCanvas.drawBitmap(bitmap, sourceRect, resizedRect, resizeFilter)
				outputBitmap = resizedBitmap
			}
		}
		return outputBitmap
	}

	/**
	 * Returns the latest rendered frame as a Bitmap, or null if:
	 *   - no new image is available in the ImageReader, OR
	 *   - the raw image plane hashes identically to the previously returned frame
	 *     (the map is idle — skip resize+encode+send entirely).
	 *
	 * The returned Bitmap is a reused reference owned by this VirtualDisplayScreenCapture
	 * instance and must be consumed before the next call to getFrame(); callers are
	 * expected to serialize access (see FrameUpdater.encoderBusy gate).
	 */
	fun getFrame(): Bitmap? {
		val image = imageCapture.acquireLatestImage() ?: return null
		try {
			val hash = hashImagePlane(image)
			if (hash == lastFrameHash) {
				return null
			}
			lastFrameHash = hash
			return convertToBitmap(image)
		} finally {
			image.close()
		}
	}

	private fun hashImagePlane(image: Image): Long {
		val buf: ByteBuffer = image.planes[0].buffer
		val start = buf.position()
		val end = buf.limit() - 8
		val span = end - start
		if (span < 8) return EMPTY_FRAME_HASH
		// Walk the buffer in HASH_SAMPLE_COUNT evenly-spaced 8-byte windows. Absolute gets
		// don't touch the buffer's position, so this is non-mutating and allocation-free.
		val step = ((span / HASH_SAMPLE_COUNT) and 0x7FFFFFF8).coerceAtLeast(8)
		var h = FNV_OFFSET_BASIS
		var i = start
		var sampled = 0
		while (i <= end && sampled < HASH_SAMPLE_COUNT) {
			h = (h xor buf.getLong(i)) * FNV_PRIME
			i += step
			sampled++
		}
		// Avoid colliding with EMPTY_FRAME_HASH, which is reserved for "force-send next frame".
		return if (h == EMPTY_FRAME_HASH) 1L else h
	}

	fun compressBitmap(bitmap: Bitmap): ByteArray {
		// send to car
		outputFile.reset()
		bitmap.compress(screenCaptureConfig.compressFormat, screenCaptureConfig.compressQuality, outputFile)
		return outputFile.toByteArray()
	}

	fun onDestroy() {
		this.imageCapture.setOnImageAvailableListener(null, null)
	}
}
