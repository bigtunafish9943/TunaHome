#!/usr/bin/env bash
# Runs a new TunaHome on an Android TV emulator: installs it, goes through the main screens with
# the remote's buttons, takes a screenshot of each, and fails if TunaHome stopped with an error
# (or stopped answering) at any point.
#
#   check-on-tv.sh <apk> <folder for screenshots and logs>
set -uo pipefail

APK="$1"
OUT="$2"
PKG=com.tunastream.home
mkdir -p "$OUT"

# A screenshot, after waiting for the screen to settle
shot() {
  sleep "${2:-3}"
  adb exec-out screencap -p > "$OUT/$1.png" || echo "No screenshot for $1"
  echo "Screenshot: $1"
}

# Remote buttons, one after another
key() {
  for k in "$@"; do
    adb shell input keyevent "$k"
    sleep 1
  done
}

# Something on the home screen (a shortcut action), the way a shortcut asks for it
home() {
  if [ -n "${2:-}" ]; then
    adb shell am start -n "$PKG/.HomeActivity" --es hotkey_action "$1" --es hotkey_arg "$2" > /dev/null
  else
    adb shell am start -n "$PKG/.HomeActivity" --es hotkey_action "$1" > /dev/null
  fi
}

adb wait-for-device
adb shell settings put global stay_on_while_plugged_in 7 || true
adb logcat -c || true
adb logcat -b crash -c || true

echo "Installing $APK"
adb install -r "$APK" || { echo "Install failed"; exit 1; }

# First start: the welcome
adb shell am start -n "$PKG/.HomeActivity" > /dev/null
shot 01-welcome 12
key KEYCODE_BACK
shot 02-home 5
key KEYCODE_DPAD_RIGHT
shot 03-home-next-app 2

home side_menu
shot 04-side-menu 3
key KEYCODE_BACK

home quick_changes
shot 05-quick-changes 3
key KEYCODE_BACK

home settings_page main
shot 06-settings 4
key KEYCODE_BACK

home settings_page fine
shot 07-fine-tuning 4
key KEYCODE_DPAD_UP
shot 08-fine-tuning-preview 2
# The first row is the ready-made styles: choose Neon (the fourth), then look again
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
sleep 1
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
shot 09-fine-tuning-neon 4
key KEYCODE_DPAD_UP
shot 10-fine-tuning-neon-preview 2
key KEYCODE_BACK

home settings_page pictures
shot 11-icons 4
key KEYCODE_BACK

home settings_page background
shot 12-background 4
key KEYCODE_BACK

home settings_page sections
shot 13-sections 4
key KEYCODE_BACK

home home_top
key KEYCODE_DPAD_RIGHT
shot 14-home-neon 3

# The screen saver, as the TV would start it
adb shell settings put secure screensaver_components "$PKG/.Screensaver" || true
if adb shell am start -n com.android.systemui/.Somnambulator > /dev/null 2>&1; then
  shot 15-screen-saver 8
  key KEYCODE_BACK
fi

# Anything TunaHome said while it ran, for reading if something looks wrong
adb logcat -d > "$OUT/logcat.txt" || true
adb logcat -d -b crash > "$OUT/crash.txt" || true

problems=0
if grep -q "Process: $PKG" "$OUT/crash.txt"; then
  echo "TunaHome stopped with an error:"
  grep -A 30 "Process: $PKG" "$OUT/crash.txt" | head -60
  problems=1
fi
if grep -q "ANR in $PKG" "$OUT/logcat.txt"; then
  echo "TunaHome stopped answering:"
  grep -A 10 "ANR in $PKG" "$OUT/logcat.txt" | head -30
  problems=1
fi
if [ -z "$(adb shell pidof $PKG | tr -d '\r')" ]; then
  echo "TunaHome isn't running at the end of the check"
  problems=1
fi

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  if [ "$problems" = 0 ]; then
    echo "TunaHome ran on an Android TV emulator through every main screen without a problem." >> "$GITHUB_STEP_SUMMARY"
  else
    echo "TunaHome had a problem on the Android TV emulator: see this job's log and the tv-check files." >> "$GITHUB_STEP_SUMMARY"
  fi
fi
exit "$problems"
