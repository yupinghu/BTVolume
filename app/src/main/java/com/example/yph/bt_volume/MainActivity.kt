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
    private var prefKey : String? = null
    private var requestSetVolumeCalled = false

    private val prefs = context.getSharedPreferences(context.getString(R.string.a2dp_prefs),
            Context.MODE_PRIVATE)

    private fun saveVolume(volume: Int) {
        prefs.edit().putInt(prefKey, volume).apply()
    }

    fun deviceConnecting(mediaRouter: MediaRouter, name: String, address: String) {
        prefKey = "$name [$address]"
        val volume = prefs.getInt(prefKey, -1)
        if (volume >= 0) {
            // Find the Bluetooth route and set its volume
            var count = mediaRouter.routeCount
            while (count > 0) {
                --count
                val route = mediaRouter.getRouteAt(count)
                if (route.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH) {
                    requestSetVolumeCalled = true
                    route.requestSetVolume(volume)
                }
            }
        }
    }

    override fun onRouteVolumeChanged(router: MediaRouter?, info: MediaRouter.RouteInfo?) {
        super.onRouteVolumeChanged(router, info)
        if (prefKey != null && info?.deviceType == MediaRouter.RouteInfo.DEVICE_TYPE_BLUETOOTH) {
            val volume = info.volume
            Log.d("BTVolume", "Volume changed: $prefKey $volume")
            if (requestSetVolumeCalled) {
                Log.d("BTVolume", "requestSetVolume was previously called; ignoring this callback")
                requestSetVolumeCalled = false
            } else {
                saveVolume(volume)
            }
        }
    }
}

/**
 * Foreground service that owns the volume detection callback.
 * This service runs whenever there's an A2DP device connected.
 */
class MyService : Service() {
    // Track whether we've called startForeground.
    private var fg = false

    // The callback
    private lateinit var callback : MyMediaCallback

    // Notification stuff.
    private val channelId = "fg_service"
    private val notificationId = 1

    // System Services
    private lateinit var mediaRouter : MediaRouter
    private lateinit var notificationManager : NotificationManager

    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()
        Log.d("BTVolume", "Service created")

        // Get System Services
        mediaRouter = getSystemService(MediaRouter::class.java)
        notificationManager = getSystemService(NotificationManager::class.java)

        // Create the callback.
        callback = MyMediaCallback(this)
        mediaRouter.addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, callback)

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
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val extras = intent?.extras
        val name : String
        if (extras != null) {
            // Provide the device info to the callback
            name = extras.getString("name")
            callback.deviceConnecting(mediaRouter, name, extras.getString("address"))
        } else {
            // This shouldn't happen, but we need some valid string for the notification here.
            name = "unknown device"
        }

        // Setup the foreground service notification.
        val notification = Notification.Builder(this, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Tracking volume for $name")
                .build()
        if (!fg) {
            startForeground(notificationId, notification)
            fg = true
        } else {
            notificationManager.notify(notificationId, notification)
        }

        Log.d("BTVolume", "Service onStartCommand")
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onDestroy() {
        getSystemService(MediaRouter::class.java).removeCallback(callback)
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
                BluetoothProfile.STATE_CONNECTING -> {
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
