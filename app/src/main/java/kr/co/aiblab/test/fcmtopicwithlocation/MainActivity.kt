package kr.co.aiblab.test.fcmtopicwithlocation

import android.Manifest
import android.app.Activity
import android.app.NotificationManager
import android.content.*
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.location.Location
import android.net.Uri
import android.os.*
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.annotation.Nullable
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.fonfon.kgeohash.GeoHash
import com.google.android.gms.tasks.OnCompleteListener
import com.google.android.material.snackbar.Snackbar
import com.google.firebase.iid.FirebaseInstanceId
import com.google.firebase.messaging.FirebaseMessaging
import com.orhanobut.logger.AndroidLogAdapter
import com.orhanobut.logger.Logger
import com.orhanobut.logger.PrettyFormatStrategy
import kotlinx.android.synthetic.main.activity_main.*
import java.text.DateFormat
import java.util.*

class MainActivity : AppCompatActivity(), OnSharedPreferenceChangeListener, Handler.Callback {

    private var service: LocationUpdatesService? = null
    private lateinit var receiver: LocationReceiver
    private var isBound = false

    private val serviceConnection: ServiceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName, service: IBinder) {
            val binder = service as LocationUpdatesService.LocalBinder
            this@MainActivity.service = binder.service
            isBound = true
        }

        override fun onServiceDisconnected(name: ComponentName) {
            service = null
            isBound = false
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        Logger.clearLogAdapters()
        Logger.addLogAdapter(object : AndroidLogAdapter(
            PrettyFormatStrategy.newBuilder()
                .tag("SEOJAEHWA")
                .build()
        ) {
            override fun isLoggable(priority: Int, @Nullable tag: String?): Boolean {
                return BuildConfig.DEBUG
            }
        })

        super.onCreate(savedInstanceState)
        receiver = LocationReceiver(Handler(this))
        setContentView(R.layout.activity_main)

        abtn_service_on.setOnClickListener {
            if (!checkPermissions(it.context)) {
                requestPermissions(this@MainActivity)
            } else {
                service?.requestLocationUpdates()
            }
        }

        abtn_service_off.setOnClickListener {
            service?.removeLocationUpdates()
        }

        abtn_log_token.setOnClickListener {
            FirebaseInstanceId.getInstance().instanceId
                .addOnCompleteListener(OnCompleteListener { task ->
                    if (!task.isSuccessful) {
                        Logger.w("getInstanceId failed", task.exception)
                        return@OnCompleteListener
                    }
                    val token = task.result?.token
                    val msg = getString(R.string.msg_token_fmt, token)
                    Logger.d(msg)
                    Toast.makeText(baseContext, msg, Toast.LENGTH_SHORT).show()
                })

//            val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
//            val id: String = getString(R.string.default_notification_channel_id)
//            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
//                notificationManager.deleteNotificationChannel(id)
//            }
        }

        if (Utils.requestingLocationUpdates(this)) {
            if (!checkPermissions(this)) {
                requestPermissions(this@MainActivity)
            }
        }
    }

    override fun onStart() {
        super.onStart()
        PreferenceManager.getDefaultSharedPreferences(this)
            .registerOnSharedPreferenceChangeListener(this)
        setButtonsState(Utils.requestingLocationUpdates(this))
        bindService(
            Intent(this, LocationUpdatesService::class.java),
            serviceConnection,
            Context.BIND_AUTO_CREATE
        )
    }

    override fun onResume() {
        super.onResume()
        LocalBroadcastManager.getInstance(this).registerReceiver(
            receiver,
            IntentFilter(LocationUpdatesService.ACTION_BROADCAST)
        )
    }

    override fun onPause() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(receiver)
        super.onPause()
    }

    override fun onStop() {
        if (isBound) {
            unbindService(serviceConnection)
            isBound = false
        }
        PreferenceManager.getDefaultSharedPreferences(this)
            .unregisterOnSharedPreferenceChangeListener(this)
        super.onStop()
    }

    override fun onSharedPreferenceChanged(sharedPreferences: SharedPreferences, key: String) {
        if (key == Utils.KEY_REQUESTING_LOCATION_UPDATES) {
            val requestingLocationUpdates = Utils.requestingLocationUpdates(this)
            setButtonsState(requestingLocationUpdates)
//            atv_status.text = if (requestingLocationUpdates) "ON" else "OFF"
            if (!requestingLocationUpdates) {
                tv_info.text = ""
            }
        }
    }

    private fun checkPermissions(context: Context): Boolean =
        PackageManager.PERMISSION_GRANTED == ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

    private fun requestPermissions(activity: Activity) {
        val shouldProvideRationale = ActivityCompat.shouldShowRequestPermissionRationale(
            activity,
            Manifest.permission.ACCESS_FINE_LOCATION
        )

        if (shouldProvideRationale) {
            Logger.i("Displaying permission rationale to provide additional context.")
            Snackbar.make(
                findViewById<View>(R.id.activity_main),
                R.string.permission_rationale,
                Snackbar.LENGTH_INDEFINITE
            ).setAction(R.string.ok) {
                ActivityCompat.requestPermissions(
                    activity,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    REQUEST_PERMISSIONS_REQUEST_CODE
                )
            }.show()
        } else {
            Logger.i("Requesting permission")
            ActivityCompat.requestPermissions(
                activity,
                arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                REQUEST_PERMISSIONS_REQUEST_CODE
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String?>,
        grantResults: IntArray
    ) {
        Logger.i("onRequestPermissionResult")
        if (requestCode == REQUEST_PERMISSIONS_REQUEST_CODE) {
            when {
                grantResults.isEmpty() -> {
                    Logger.i("User interaction was cancelled.")
                }
                grantResults[0] == PackageManager.PERMISSION_GRANTED -> { // Permission was granted.
                    service?.requestLocationUpdates()
                }
                else -> { // Permission denied.
//                    setButtonsState(false)
                    setButtonsState(false)
                    Snackbar.make(
                        findViewById<View>(R.id.activity_main),
                        R.string.permission_denied_explanation,
                        Snackbar.LENGTH_INDEFINITE
                    ).setAction(R.string.settings) {
                        val intent = Intent()
                        intent.action = Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                        val uri = Uri.fromParts(
                            "package",
                            BuildConfig.APPLICATION_ID, null
                        )
                        intent.data = uri
                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                        startActivity(intent)
                    }.show()
                }
            }
        }
    }

    private fun setButtonsState(requestingLocationUpdates: Boolean) {
        if (requestingLocationUpdates) {
            abtn_service_on.isEnabled = false
            abtn_service_off.isEnabled = true
        } else {
            abtn_service_on.isEnabled = true
            abtn_service_off.isEnabled = false
        }
    }

    override fun handleMessage(msg: Message): Boolean {
        if (msg.what == LocationReceiver.HANDLER_MSG_LOCATION) {
            val location = msg.obj as Location
            tv_info.text = StringBuilder().apply {
                append(Utils.getLocationText(location))
                append("\n")
                append(GeoHash(location))
                append("\n")
                append(" >${DateFormat.getDateTimeInstance().format(Date())}")
            }
        }
        return true
    }

    companion object {
        private const val REQUEST_PERMISSIONS_REQUEST_CODE = 34
    }
}

