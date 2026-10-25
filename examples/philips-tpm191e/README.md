# Philips TPM191E: single-session notes and another observed unit

Checked on a Philips 50PUS8505, TP Vision TPM191E (`PH9M_EA_5599`), Android TV 12 / API 31, firmware `STT2.230526.001`. It shares the platform and firmware family of this folder's existing [`debloat.txt`](debloat.txt), but this was not a test of that full list.

## What was checked

- Disabled an owner-selected subset of 13 packages from the existing list: Philips analytics, usage logging, demo and promotion/store packages; the Sticker and Fitness Buddy preloads; and Google feedback/setup-only packages. Nothing was uninstalled.
- After one reboot, all 13 selected packages remained disabled.
- Installed the repository launcher, then disabled the stock launcher. Home resolved to `com.gathin.tvhome/.MainActivity` after installation and was focused during the navigation checks.
- The physical remote's Sources and Settings buttons continued to open Philips' internal menus. The launcher's Inputs menu reported HDMI and YPbPr, with Live TV configured as `org.droidtv.playtv`; no separate AV input was reported.

This is a single-session report, not a several-day debloat test. The full `debloat.txt` was not applied to this model. Streaming-app playback, broadcast/HDMI playback, USB-drive playback, app-shortcut remapping and long-press volume repeat were not validated here. Keep the existing list's package choices commented out unless they match your own use.

## Another unit observed: Android device name 65OLED804/12

On 25 October 2026, a Philips TV reported `65OLED804/12` as its Android device name. Its system properties identify the platform as TPM191E / `PH9M_EA_5599`, MediaTek MT5887, Android TV 12 / API 31, fingerprint `Philips/PH9M_EA_5599/PH9M_EA_5599:12/STT2.230526.001/4215:user/release-keys`, matching the firmware above.

At observation, Home still resolved to the stock `com.google.android.tvlauncher/.MainActivity`. The Freeview/TP Vision packages noted above were disabled. `com.google.android.youtube.tvmusic` was also disabled, but its disable history was not established. No additional package had been disabled for cleanup, the existing list was not applied to this unit, and this is not a debloat or launcher test for the unit reporting `65OLED804/12`.
