package kr.co.aiblab.test.fcmtopicwithlocation

import android.content.Context
import android.location.Location
import androidx.preference.PreferenceManager
import java.text.DateFormat
import java.util.*

object Utils {

    const val KEY_REQUESTING_LOCATION_UPDATES = "requesting_location_updates"
    private const val KEY_PREV_GEO_HASH = "previous_geo_hash"

    fun requestingLocationUpdates(context: Context): Boolean =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getBoolean(KEY_REQUESTING_LOCATION_UPDATES, false)

    fun setRequestingLocationUpdates(context: Context, requestingLocationUpdates: Boolean) =
        with(PreferenceManager.getDefaultSharedPreferences(context).edit()) {
            putBoolean(KEY_REQUESTING_LOCATION_UPDATES, requestingLocationUpdates)
            apply()
        }

    fun getLocationText(location: Location?): String = if (location == null) "Unknown location"
    else "${location.latitude}, ${location.longitude}"

    fun getLocationTitle(context: Context): String = context.getString(
        R.string.location_updated,
        DateFormat.getDateTimeInstance().format(Date())
    )

    fun getPrevGeoHash(context: Context): String =
        PreferenceManager.getDefaultSharedPreferences(context)
            .getString(KEY_PREV_GEO_HASH, "")!!

    fun savePrevGeoHash(context: Context, geoHash: String) =
        with(PreferenceManager.getDefaultSharedPreferences(context).edit()) {
            putString(KEY_PREV_GEO_HASH, geoHash)
            apply()
        }
}