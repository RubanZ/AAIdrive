package me.hufman.androidautoidrive

import android.os.Handler
import android.os.Looper
import android.util.Log
import de.bmw.idrive.BMWRemoting
import io.bimmergestalt.idriveconnectkit.android.IDriveConnectionObserver
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.job
import java.io.IOException
import kotlin.coroutines.CoroutineContext

const val TAG = "CarThread"
/**
 * A thread subclass that swallows errors when the car disconnects
 * It also sets up an Android Looper
 */
class CarThread(name: String, var runnable: () -> (Unit)): Thread(name) {
	var handler: Handler? = null
	val iDriveConnectionObserver = IDriveConnectionObserver()

	init {
		isDaemon = true
	}

	override fun run() {
		try {
			Looper.prepare()
			handler = Handler(Looper.myLooper()!!)
			runnable()
			runnable = {}
			Log.i(TAG, "Successfully finished runnable for thread $name, starting Handler loop")
			Looper.loop()
			Log.i(TAG, "Successfully finished tasks for thread $name")
		} catch (e: IllegalStateException) {
			// posted to a dead handler
			Log.i(TAG, "Shutting down thread $name due to IllegalStateException: $e", e)
		} catch (e: org.apache.etch.util.TimeoutException) {
			// phone was unplugged during an RPC command
			Log.i(TAG, "Shutting down thread $name due to Etch TimeoutException")
		} catch (e: BMWRemoting.SecurityException) {
			// the car rejected the SAS certificate (expired / mismatched signing oracle / etc).
			// This is a checked Exception (not a RuntimeException), so without this catch it
			// would escape every other handler and kill the whole app process — taking down
			// Map, Notifications, etc. along with it. Swallow it here so each CarAppService
			// can fail independently.
			Log.w(TAG, "Shutting down thread $name due to BMWRemoting SecurityException: errorId=${e.errorId} msg=${e.errorMsg}")
		} catch (e: RuntimeException) {
			// phone was unplugged during an RPC command
			Log.i(TAG, "Shutting down thread $name due to RuntimeException: $e", e)
		} catch (e: IOException) {
			val cause = e.cause
			if (!iDriveConnectionObserver.isConnected) {
				// the car is no longer connected
				// so this is most likely a crash caused by the closed connection
				Log.i(TAG, "Shutting down thread $name due to disconnection")
			} else if (cause is org.apache.etch.util.TimeoutException) {
				Log.i(TAG, "Shutting down thread $name due to Etch TimeoutException")
			} else if (cause is RuntimeException) {
				Log.i(TAG, "Shutting down thread $name due to RuntimeException: $cause", cause)
			} else if (cause is BMWRemoting.ServiceException && cause.errorMsg.contains("RHMI application was already connected")) {
				// sometimes, the BCL tunnel blips during the start of the connection
				// and so previously-initialized apps are still "in the car" though the tunnel has since restarted
				// and so the car complains that the app is already connected
				// so shut down the thread for now and wait for MainService to start this app module again
				Log.i(TAG, "RHMI application was already connected, perhaps from a previous partial connection, shutting down thread $name")
			} else {
				throw(e)
			}
		} finally {
			// if we fail during init, make sure to forget the runnable
			runnable = {}
		}
	}

	fun post(block: () -> Unit) {
		if (handler?.looper?.thread?.isAlive == true) {
			handler?.post(block)
		}
	}

	fun quit() {
		handler?.looper?.quit()
		handler = null      // no longer useful
	}

	fun quitSafely() {
		handler?.looper?.quitSafely()
		handler = null      // no longer useful
	}
}

var CarThreadExceptionHandler = CoroutineExceptionHandler { c: CoroutineContext, e: Throwable ->
	when (e) {
		is IllegalStateException -> {
			// posted to a dead handler
			Log.i(TAG, "Shutting down coroutine thread $c due to IllegalStateException: $e", e)
		}
		is org.apache.etch.util.TimeoutException -> {
			// phone was unplugged during an RPC command
			Log.i(TAG, "Shutting down coroutine thread $c due to Etch TimeoutException")
		}
		is BMWRemoting.ServiceException -> {
			// probably phone was unplugged during an RPC command
			Log.i(TAG, "Shutting down coroutine thread $c due to ServiceException: $e", e)
		}
		is RuntimeException -> {
			// phone was unplugged during an RPC command
			Log.i(TAG, "Shutting down coroutine thread $c due to RuntimeException: $e", e)
		}
		is IOException -> {
			// probably phone was unplugged during an RPC command
			Log.i(TAG, "Shutting down coroutine thread $c due to ServiceException: $e", e)
		}
		else -> throw e
	}
}