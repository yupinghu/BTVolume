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

fun getPrefs(c: Context): SharedPreferences {
    return c.getSharedPreferences(c.getString(R.string.a2dp_prefs), Context.MODE_PRIVATE)
}

fun saveVolume(prefs: SharedPreferences, name: String, volume: Int) {
    prefs.edit().putInt(name, volume).apply()
}

class MyMediaCallback(private val context : Context) : MediaRouter.SimpleCallback() {
    override fun onRouteVolumeChanged(router: MediaRouter?, info: MediaRouter.RouteInfo?) {
        super.onRouteVolumeChanged(router, info)
        if (info != null) {
            val deviceType = info.deviceType
            val name = info.name
            val volume = info.volume
            Log.d("BT_Volume", "MMC: $deviceType $name $volume")
            saveVolume(getPrefs(context), name.toString(), volume)
        }
    }
}

class MyService : Service() {
    private var callback : MyMediaCallback? = null

    private fun makeNotification() : Notification {
        val channelId = "fg_service"
        val channelName = getString(R.string.fg_service_notif_channel_name)
        val importance = NotificationManager.IMPORTANCE_NONE
        val channel = NotificationChannel(channelId, channelName, importance)
        channel.description = getString(R.string.fg_service_notif_channel_description)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)

        return Notification.Builder(this, channelId)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("Title")
                .setContentText("whee!")
                .build()
    }


    override fun onBind(intent: Intent?): IBinder? {
        return null
    }

    override fun onCreate() {
        super.onCreate()

        startForeground(1, makeNotification())
        callback = MyMediaCallback(this)
        getSystemService(MediaRouter::class.java)
                .addCallback(MediaRouter.ROUTE_TYPE_LIVE_AUDIO, callback)
        Log.d("BT_Volume", "Service created")
    }

    override fun onDestroy() {
        super.onDestroy()
        getSystemService(MediaRouter::class.java).removeCallback(callback)
        Log.d("BT_Volume", "Service destroyed")
    }
}

class MyReceiver() : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager
        val type = when (intent?.action) {
            BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED -> "a2dp"
            BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED -> "headset"
            else -> null
        }

        if (intent != null && audioManager != null) {
            val device = intent.extras.getParcelable<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE)
            val state = intent.extras.getInt(BluetoothProfile.EXTRA_STATE)
            val name = device.name
            val volume = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
            Log.d("BT_Volume", "$type: $device, $name, $state, $volume")
            when (state) {
                BluetoothProfile.STATE_CONNECTING -> {
                    if (context != null) {
                        val prefs = getPrefs(context)
                        if (prefs.contains(name)) {
                            val newVolume = prefs.getInt(name, volume)
                            audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, newVolume, AudioManager.FLAG_SHOW_UI)
                        } else {
                            saveVolume(prefs, name, volume)
                        }
                        context.startForegroundService(Intent(context, MyService::class.java))
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
    }
}
