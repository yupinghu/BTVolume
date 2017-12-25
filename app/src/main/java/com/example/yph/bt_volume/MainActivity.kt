package com.example.yph.bt_volume

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.bluetooth.BluetoothA2dp
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothHeadset
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.media.AudioManager
import android.media.MediaRouter
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.TextView

/**
 * Get the SharedPreferences where we store the volume levels.
 */
fun getPrefs(c: Context): SharedPreferences {
    return c.getSharedPreferences(c.getString(R.string.a2dp_prefs), Context.MODE_PRIVATE)
}

fun getPrefKey(name: String, address: String): String {
    return "$name [$address]"
}

fun getVolume(prefs: SharedPreferences, name: String, address: String): Int {
    return prefs.getInt(getPrefKey(name, address), -1)
}

/**
 * Write the volume for a specific device to the SharedPreferences.
 */
fun saveVolume(prefs: SharedPreferences, name: String, address: String, volume: Int) {
    prefs.edit().putInt(getPrefKey(name, address), volume).apply()
}

/**
 * Callback to detect when Bluetooth volume changes occur.
 */
class MyMediaCallback(private val context : Context) : MediaRouter.SimpleCallback() {
    // The device address is set by the service; we update this address's value in the preferences.
    var address : String? = null

    override fun onRouteVolumeChanged(router: MediaRouter?, info: MediaRouter.RouteInfo?) {
        super.onRouteVolumeChanged(router, info)
        val addr = address
        if (addr != null && info?.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH) {
            val name = info.name
            val volume = info.volume
            Log.d("BTVolume", "Volume changed: $name $addr $volume")
            saveVolume(getPrefs(context), name.toString(), addr, volume)
        }
    }
}

/**
 * Foreground service that owns the volume detection callback.
 * This service runs whenever there's an A2DP device connected.
 */
class MyService : Service() {
    // The callback
    private lateinit var callback : MyMediaCallback

    // Notification stuff, so that it can be updated.
    private val notificationId = 1
    private lateinit var notificationBuilder : Notification.Builder

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("BTVolume", "Service created")

        // Setup the callback.
        callback = MyMediaCallback(this)
        getSystemService(MediaRouter::class.java)
                .addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, callback)

        // Create the notification channel.
        val channelId = "fg_service"
        val channelName = getString(R.string.fg_service_notif_channel_name)
        val importance = NotificationManager.IMPORTANCE_LOW
        val channel = NotificationChannel(channelId, channelName, importance)
        channel.description = getString(R.string.fg_service_notif_channel_description)
        channel.setShowBadge(false)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        // Create the builder
        notificationBuilder = Notification.Builder(this, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Tracking Volume")

        // This service must be in the foreground since the app itself basically doesn't run.
        startForeground(notificationId, notificationBuilder.build())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Extract the device specifics from the intent extras.
        val extras = intent?.extras
        if (extras != null) {
            // Update the notification to include the device info.
            val address = extras.getString("address")
            val name = extras.getString("name")
            notificationBuilder.setContentTitle("Tracking volume for $name")
            getSystemService(NotificationManager::class.java)
                    .notify(notificationId, notificationBuilder.build())

            // Let the callback know which device is connected.
            callback.address = address
        }
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        super.onDestroy()
        getSystemService(MediaRouter::class.java).removeCallback(callback)
        Log.d("BTVolume", "Service destroyed")
    }
}

/**
 * BroadcastReceiver for the Bluetooth CONNECTION_STATE_CHANGED actions.
 * Currently we only handle A2DP; eventually we might try to store e.g. call volumes too.
 */
class MyReceiver() : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager

        val type = when (intent?.action) {
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> "a2dp"
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> "headset"
            else -> null
        }

        if (intent != null && type != null && audioManager != null) {
            val state = intent.extras.getInt(BluetoothProfile.EXTRA_STATE)
            val device = intent.extras.getParcelable<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val name = device.name
            val address = device.address
            val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            Log.d("BTVolume", "$name, $address, $state, $volume")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    if (context != null) {
                        // Check preferences for this device
                        val prefs = getPrefs(context)
                        val volumePref = getVolume(prefs, name, address)
                        if (volumePref < 0) {
                            // This is a new device: add it to the prefs.
                            saveVolume(prefs, name, address, volume)
                        } else {
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, volumePref,
                                    AudioManager.FLAG_SHOW_UI)
                        }

                        // Start the service in order to track volume changes.
                        context.startForegroundService(Intent(context, MyService::class.java)
                                .putExtra("name", name)
                                .putExtra("address", address))
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    context?.stopService(Intent(context, MyService::class.java))
                }
            }
        }
    }
}

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        val prefs = getPrefs(this)
        val textView = findViewById<TextView>(R.id.hello)
        var text = ""
        for (pref in prefs.all) {
            text += pref.key + " : " + pref.value + "\n"
        }
        textView.text = text
    }
}
