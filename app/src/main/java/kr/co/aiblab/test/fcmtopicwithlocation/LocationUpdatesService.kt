package kr.co.aiblab.test.fcmtopicwithlocation

import android.app.*
import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.location.Location
import android.os.*
import android.text.TextUtils
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import com.fonfon.kgeohash.GeoHash
import com.google.android.gms.location.*
import com.google.firebase.messaging.FirebaseMessaging
import com.orhanobut.logger.Logger


class LocationUpdatesService : Service() {

    private val binder: IBinder = LocalBinder()
    private lateinit var locationRequest: LocationRequest
    private lateinit var fusedLocationClient: FusedLocationProviderClient
    private lateinit var locationCallback: LocationCallback
    private lateinit var handler: Handler
    private lateinit var location: Location
    private lateinit var notificationManager: NotificationManager
    private var changingConfiguration: Boolean = false

    override fun onCreate() {
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this)
        locationCallback = object : LocationCallback() {
            override fun onLocationResult(locationResult: LocationResult) {
                super.onLocationResult(locationResult)
                onNewLocation(locationResult.lastLocation)
            }
        }

        createLocationRequest()
        getLastLocation()

        val handlerThread = HandlerThread(LocationUpdatesService::class.java.simpleName)
        handlerThread.start()
        handler = Handler(handlerThread.looper)

        notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        createNotificationChannels(notificationManager)
    }

    override fun onStartCommand(intent: Intent, flags: Int, startId: Int): Int {
        val startedFromNotification = intent.getBooleanExtra(
            EXTRA_STARTED_FROM_NOTIFICATION,
            false
        )
        if (startedFromNotification) {
            removeLocationUpdates()
            stopSelf()
        }
        return START_NOT_STICKY
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        changingConfiguration = true
    }

    override fun onBind(intent: Intent): IBinder? {
        Logger.i("in onBind()")
        stopForeground(true)
        changingConfiguration = false
        return binder
    }

    override fun onRebind(intent: Intent) {
        Logger.i("in onRebind()")
        stopForeground(true)
        changingConfiguration = false
        super.onRebind(intent)
    }

    override fun onUnbind(intent: Intent): Boolean {
        Logger.i("Last client unbound from service")
        if (!changingConfiguration and Utils.requestingLocationUpdates(this)) {
            Logger.i("Starting foreground service")
            startForeground(NOTIFICATION_ID, getNotification())
        }
        return true
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
    }

    fun requestLocationUpdates() {
        Logger.i("Requesting location updates")
        Utils.setRequestingLocationUpdates(this, true)
        startService(Intent(applicationContext, LocationUpdatesService::class.java))
        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback,
                Looper.myLooper()
            )
        } catch (e: SecurityException) {
            Utils.setRequestingLocationUpdates(this, false)
            Logger.e("Lost location permission. Could not request updates.", e)
        }
    }

    fun removeLocationUpdates() {
        Logger.i("Removing location updates")
        try {
            fusedLocationClient.removeLocationUpdates(locationCallback)
            Utils.setRequestingLocationUpdates(this, false)
            stopSelf()
        } catch (e: SecurityException) {
            Utils.setRequestingLocationUpdates(this, true)
            Logger.e("Lost location permission. Could not remove updates.", e)
        }
    }

    private fun onNewLocation(location: Location) {
        this.location = location
        val geoHash = GeoHash(location)
        Logger.i("New location: $location\n >> GeoHash: $geoHash")

        val intent = Intent(ACTION_BROADCAST)
        intent.putExtra(EXTRA_LOCATION, location)
        LocalBroadcastManager.getInstance(applicationContext).sendBroadcast(intent)

        // TODO goHash 값을 얻어오고 topic 을 구독, 해제 처리 함
        subscribeTopicUpdates(location)

        if (serviceIsRunningInForeground(this)) {
            notificationManager.notify(NOTIFICATION_ID, getNotification())
        }
    }

    private fun subscribeTopicUpdates(location: Location) {
        val geoHash = GeoHash(location, 4).toString()
        val prevGeoHash = Utils.getPrevGeoHash(this)

        if (!TextUtils.equals(geoHash, prevGeoHash)) {
            Utils.savePrevGeoHash(this, geoHash)
            with(FirebaseMessaging.getInstance()) {
                if (!TextUtils.isEmpty(prevGeoHash)) {
                    unsubscribeFromTopic(prevGeoHash).addOnCompleteListener {
                        if (it.isSuccessful) {
                            Logger.d("--- $prevGeoHash topic is unsubscribed.")
                        } else {
                            Logger.w("--- $prevGeoHash topic unsubscribing failed!", it.exception)
                        }
                    }
                }
                subscribeToTopic(geoHash).addOnCompleteListener {
                    if (it.isSuccessful) {
                        Logger.d("+++ $geoHash topic is subscribed.")
                    } else {
                        Logger.w("+++ $geoHash topic subscribing failed!", it.exception)
                    }
                }
            }
        } else {
//            Logger.i("Same as saved geoHash. Nothing to do!")
        }
    }

    private fun getNotification(): Notification {
        val text = Utils.getLocationText(location)
        val intent = Intent(this, LocationUpdatesService::class.java)
        intent.putExtra(EXTRA_STARTED_FROM_NOTIFICATION, true)
        val servicePendingIntent = PendingIntent.getService(
            this,
            0,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT
        )

        val activityPendingIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(
                this,
                MainActivity::class.java
            ), 0
        )

        return NotificationCompat.Builder(this, CHANNEL_ID).apply {
            addAction(
                R.drawable.ic_launch,
                getString(R.string.launch_activity),
                activityPendingIntent
            )
            addAction(
                R.drawable.ic_cancel,
                getString(R.string.remove_location_updates),
                servicePendingIntent
            )
            setDefaults(NotificationCompat.DEFAULT_SOUND or NotificationCompat.DEFAULT_LIGHTS)
            setContentText(text)
            setContentTitle(Utils.getLocationTitle(this@LocationUpdatesService))
            setOngoing(true)
            priority = NotificationCompat.PRIORITY_HIGH
            setSmallIcon(R.mipmap.ic_launcher_round)
            setTicker(text)
            setWhen(System.currentTimeMillis())
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                setChannelId(CHANNEL_ID)
//            }
        }.build()
    }

    private fun createNotificationChannels(manager: NotificationManager) {
        // FIXME notification 처리를 도와주는 helper class 를 만들어 기능 분리 필요
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val locationNotificationChannel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.app_name),
                NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                enableVibration(false)
            }
            val fcmNotificationChannel = NotificationChannel(
                getString(R.string.default_notification_channel_id),
                getString(R.string.default_notification_channel_name),
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                enableVibration(true)
            }
            manager.createNotificationChannels(
                listOf(
                    locationNotificationChannel,
                    fcmNotificationChannel
                )
            )
        }
    }

    private fun createLocationRequest() {
        locationRequest = LocationRequest().apply {
            interval = UPDATE_INTERVAL_IN_MILLISECONDS
            fastestInterval = FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS
            priority = LocationRequest.PRIORITY_HIGH_ACCURACY
        }
    }

    private fun getLastLocation() {
        try {
            fusedLocationClient.lastLocation.addOnCompleteListener { task ->
                if (task.isSuccessful) {
                    task.result?.let {
                        location = it
                    }
                } else {
                    Logger.w("Failed to get location")
                }
            }
        } catch (e: SecurityException) {
            Logger.e("Lost location permission.", e)
        }
    }

    inner class LocalBinder : Binder() {
        val service = this@LocationUpdatesService
    }

    private fun serviceIsRunningInForeground(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        for (service in manager.getRunningServices(Integer.MAX_VALUE)) {
            if (javaClass.name == service.service.className) {
                if (service.foreground) return true
            }
        }
        return false
    }

    companion object {
        private const val PACKAGE_NAME = "kr.co.aiblab.test.fcmtopicwithlocation"
        const val ACTION_BROADCAST = "$PACKAGE_NAME.broadcast"
        const val EXTRA_LOCATION = "$PACKAGE_NAME.location"
        private const val UPDATE_INTERVAL_IN_MILLISECONDS: Long = 20000
        private const val FASTEST_UPDATE_INTERVAL_IN_MILLISECONDS =
            UPDATE_INTERVAL_IN_MILLISECONDS / 2
        private const val CHANNEL_ID = "channel_01"
        private const val EXTRA_STARTED_FROM_NOTIFICATION =
            "$PACKAGE_NAME.started_from_notification"
        private const val NOTIFICATION_ID = 12345678
    }
}