package me.hufman.androidautoidrive.maps

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.util.Log
import me.hufman.androidautoidrive.carapp.maps.YandexMapsProjection

/**
 * No-op `ContentProvider` whose only purpose is to side-effect MapKit
 * initialization onto the **application main thread** during process startup.
 *
 * Android instantiates declared ContentProviders during `Application.attach()`,
 * before any Service or Activity reaches `onCreate`. This is the standard pattern
 * for libraries that need a Context but cannot rely on the host app subclassing
 * `Application` (Firebase, AndroidX Startup, etc. all use it).
 *
 * Why this exists for AAIdrive: Yandex MapKit's native bindings are pinned to
 * the thread that calls `MapKitFactory.initialize(context)`. AAIdrive's
 * `MapAppService.onCarStart()` runs on `CarThread`, not the main looper, so
 * initializing from there crashes inside `libmaps-mobile.so` on the first
 * `SearchFactory.getInstance().createSearchManager(...)` call (PITFALLS C2 +
 * observed `data_app_native_crash` in
 * `Java_com_yandex_mapkit_search_internal_SearchBinding_createSearchManager`).
 *
 * `ApplicationCallbacks` is `final` in main source so we can't subclass it
 * without touching shared code; this ContentProvider-as-Application-init shim
 * is the lightest-touch alternative.
 *
 * Declared only in `app/src/yandexmap/AndroidManifest.xml`. Other map flavors
 * never see it. The `query`/`insert`/etc. methods all throw because nothing
 * should call them — the provider exists purely for the side effect of `onCreate`.
 */
class YandexMapKitInitProvider : ContentProvider() {
	companion object {
		private const val TAG = "YandexMapKitInit"
	}

	override fun onCreate(): Boolean {
		val context = context ?: return false
		try {
			// Delegate to the single source of truth — same AtomicBoolean guard
			// the rest of the flavor uses, so subsequent callsites (which still
			// invoke ensureMapKitInitialized defensively) become no-ops instead
			// of trying to call setApiKey on an already-initialized MapKit (which
			// throws AssertionError, observed 2026-04-14 10:08:55).
			YandexMapsProjection.ensureMapKitInitialized(context.applicationContext)
			Log.i(TAG, "Yandex MapKit initialized on ${Thread.currentThread().name}")
		} catch (e: Throwable) {
			Log.e(TAG, "Yandex MapKit initialization failed", e)
		}
		return true
	}

	override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
	override fun getType(uri: Uri): String? = null
	override fun insert(uri: Uri, values: ContentValues?): Uri? = null
	override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
	override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
