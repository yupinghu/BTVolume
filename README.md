# BTVolume

Track per-device volume for Bluetooth media devices. For whatever reason on my Pixel 2, every time a
headset/speaker/whatever connects, its volume is reset to some default amount. This app remembers
the last volume it saw for a device and restores that volume whenever that device reconnects.

This app is for Oreo and up; I haven't done the compatibility thing for older OSes because, well, I
wrote it for my Pixel 2.

In principle, it should be possible to check the volume level as the device is disconnecting and
store that. However, the CONNECTION_STATE_CHANGED action occurs too late, after the volume has
already reverted to the internal speaker. So, instead, while an A2DP device is connected, there's
a foreground service that receives MediaRouter callbacks on volume changes. It's a bit regrettable
and I might eventually make this an option with a manual sync button.

My employer doesn't permit me to publish apps on the Play Store, otherwise I would. But hey, open
source, feel free to build and install it.
