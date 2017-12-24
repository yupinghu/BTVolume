# BTVolume

Track per-device volume for Bluetooth media devices.

For whatever reason, every time a headset/speaker/whatever connects, its volume is reset to some
default amount. This app remembers the last volume it saw for a device and restores that volume
whenever that device reconnects.

Some notes:
- The app tracks by device name; if you change the name or something, that breaks the memory.
- Whenever a device is connected, a foreground service runs to get callbacks on volume changes.
  This is a bit annoying -- in principle it should only need to check the volume when the device
  disconnects -- but I couldn't get the timing right, by the time the device disconnect broadcast
  happens, the volume has already reverted to the non-Bluetooth media volume.

My employer doesn't permit me to publish apps on the Play Store, otherwise this would just go there.
