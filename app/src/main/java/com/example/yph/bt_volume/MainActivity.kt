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
import android.media.MediaRouter
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.os.IBinder
import android.util.Log
import android.widget.TextView

/**
 * Callback to detect when Bluetooth volume changes occur.
 */
class MyMediaCallback(context : Context) : MediaRouter.SimpleCallback() {
    private val mediaRouter = context.getSystemService(MediaRouter::class.java)
    private val prefs = context.getSharedPreferences(context.getString(R.string.a2dp_prefs),
            Context.MODE_PRIVATE)

    private var prefKey : String? = null
    private var initialVolume = -1
    private var volume = -1

    private fun saveVolume() {
        if (volume >= 0 && volume != initialVolume) {
            Log.d("BTVolume", "Saving volume for $prefKey: $volume")
            prefs.edit().putInt(prefKey, volume).apply()
        }
    }

    private fun getRoute() : MediaRouter.RouteInfo? {
        var count = mediaRouter.routeCount
        while (count > 0) {
            --count
            val route = mediaRouter.getRouteAt(count)
            if (route.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH) {
                return route
            }
        }
        return null
    }

    fun deviceConnecting(name: String, address: String) : String {
        if (prefKey == null) {
            // First time the key is set: register the callback
            mediaRouter.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, this)
        } else {
            // We were already tracking another device, save it before we switch to new device
            saveVolume()
        }
        prefKey = address

        initialVolume = prefs.getInt(prefKey, -1)
        volume = -1

        Log.d("BTVolume", "Device connecting: $name $address with initial volume: $initialVolume")

        val route = getRoute()
        if (route != null && initialVolume >= 0) {
            route.requestSetVolume(initialVolume)
        }

        return route?.name?.toString() ?: name
    }

    fun deviceDisconnecting() {
        saveVolume()
        prefKey = null
        mediaRouter.removeCallback(this)
    }

    override fun onRouteVolumeChanged(router: MediaRouter?, info: MediaRouter.RouteInfo?) {
        super.onRouteVolumeChanged(router, info)
        if (info?.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH) {
            volume = info.volume
            Log.d("BTVolume", "Volume changed: $prefKey $volume")
        }
    }
}

/**
 * Foreground service that owns the volume detection callback.
 * This service runs whenever there's an A2DP device connected.
 */
class MyService : Service() {
    private var hasRun = false

    // The callback
    private lateinit var callback : MyMediaCallback

    // Notification stuff.
    private val channelId = "fg_service"
    private val notificationId = 1

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("BTVolume", "Service created")
        callback = MyMediaCallback(this)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val extras = intent?.extras
        val name = if (extras != null) {
            // Provide the device info to the callback
            callback.deviceConnecting(extras.getString("name"), extras.getString("address"))
        } else {
            // This shouldn't happen, but we need some valid string for the notification here.
            "unknown device"
        }

        val notificationManager = getSystemService(NotificationManager::class.java)
        // Setup the foreground service notification.
        val notification = Notification.Builder(this, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Tracking volume for $name")
                .build()

        if (!hasRun) {
            // Create the notification channel if necessary
            val existingChannel = notificationManager.getNotificationChannel(channelId)
            if (existingChannel == null) {
                val channelName = getString(R.string.fg_service_notif_channel_name)
                val importance = NotificationManager.IMPORTANCE_LOW
                val channel = NotificationChannel(channelId, channelName, importance)
                channel.description = getString(R.string.fg_service_notif_channel_description)
                channel.setShowBadge(false)
                channel.lockscreenVisibility = Notification.VISIBILITY_SECRET
                notificationManager.createNotificationChannel(channel)
            }

            startForeground(notificationId, notification)

            hasRun = true
        } else {
            notificationManager.notify(notificationId, notification)
        }

        Log.d("BTVolume", "Service onStartCommand")
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        callback.deviceDisconnecting()
        Log.d("BTVolume", "Service onDestroy")
        super.onDestroy()
    }
}

/**
 * BroadcastReceiver for the Bluetooth CONNECTION_STATE_CHANGED actions.
 * This just starts and stops the service as appropriate.
 * Currently we only handle A2DP; eventually we might try to store e.g. call volumes too.
 */
class MyReceiver() : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val type = when (intent?.action) {
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> "a2dp"
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> "headset"
            else -> null
        }
        val extras = intent?.extras

        if (extras != null && type != null) {
            val state = extras.getInt(BluetoothProfile.EXTRA_STATE)
            val device = extras.getParcelable<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val name = device.name
            val address = device.address
            Log.d("BTVolume", "BroadcastReceiver: $type, $name, $address, $state")
            when (state) {
                BluetoothProfile.STATE_CONNECTED -> {
                    context?.startForegroundService(Intent(context, MyService::class.java)
                            .putExtra("name", name)
                            .putExtra("address", address))
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
        val prefs = getSharedPreferences(getString(R.string.a2dp_prefs), Context.MODE_PRIVATE)
        val textView = findViewById<TextView>(R.id.hello)
        var text = ""
        for (pref in prefs.all) {
            text += pref.key + " : " + pref.value + "\n"
        }
        textView.text = text
    }
}
