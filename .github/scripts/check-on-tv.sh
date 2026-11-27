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

home settings_page profiles
shot 15-whos-watching 4
key KEYCODE_BACK

# Snow, through Quick changes: Effect is the 14th entry, Snow the 5th choice
home quick_changes
sleep 2
for i in $(seq 13); do adb shell input keyevent KEYCODE_DPAD_DOWN; done
key KEYCODE_DPAD_CENTER
sleep 1
for i in $(seq 4); do adb shell input keyevent KEYCODE_DPAD_DOWN; done
key KEYCODE_DPAD_CENTER
sleep 4
key KEYCODE_BACK
shot 16-snow 3
# Then autumn leaves: the list opens on the current choice (Snow), three above leaves
home quick_changes
sleep 2
for i in $(seq 13); do adb shell input keyevent KEYCODE_DPAD_DOWN; done
key KEYCODE_DPAD_CENTER
sleep 1
for i in $(seq 3); do adb shell input keyevent KEYCODE_DPAD_DOWN; done
key KEYCODE_DPAD_CENTER
sleep 4
key KEYCODE_BACK
shot 17-leaves 3

# Rain, then a storm: the list opens on Leaves, rain is two above it and storm one below rain
home quick_changes
sleep 2
for i in $(seq 13); do adb shell input keyevent KEYCODE_DPAD_DOWN; done
key KEYCODE_DPAD_CENTER
sleep 1
key KEYCODE_DPAD_UP KEYCODE_DPAD_UP KEYCODE_DPAD_CENTER
sleep 4
key KEYCODE_BACK
shot 17b-rain 3
home quick_changes
sleep 2
for i in $(seq 13); do adb shell input keyevent KEYCODE_DPAD_DOWN; done
key KEYCODE_DPAD_CENTER
sleep 1
key KEYCODE_DPAD_DOWN KEYCODE_DPAD_CENTER
sleep 4
key KEYCODE_BACK
shot 17c-storm 3

# Set up from your phone: the TV screen, and its web page answering
home phone_setup
shot 18-phone-setup 4
adb forward tcp:8686 tcp:8686 || true
page=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 http://127.0.0.1:8686/ || echo none)
denied=$(curl -s -o /dev/null -w "%{http_code}" --max-time 10 -X POST -d '{"code":"000000"}' http://127.0.0.1:8686/api/login || echo none)
echo "Phone page: $page, wrong code: $denied"
phone_ok=1
if [ "$page" != "200" ] || [ "$denied" != "403" ]; then
  echo "The phone setup page didn't answer as it should"
  phone_ok=0
fi
key KEYCODE_BACK

# Who's watching: start it with "Sam" (this setup) and add "Kids" as a copy, then switch to Kids
home settings_page profiles
sleep 3
key KEYCODE_DPAD_CENTER
sleep 2
adb shell input text Sam
key KEYCODE_ENTER
sleep 2
adb shell input text Kids
key KEYCODE_ENTER
sleep 2
key KEYCODE_DPAD_CENTER
shot 20-people 5
key KEYCODE_BACK
home whos_watching
shot 21-whos-watching 4
# The chooser only stays open when there are two people or more
switched=1
if ! adb shell dumpsys activity activities | grep -q "WhoActivity"; then
  echo "Who's watching wasn't set up as expected"
  switched=0
fi
key KEYCODE_DPAD_RIGHT KEYCODE_DPAD_CENTER
# TunaHome starts again in Kids' setup
sleep 10
adb shell am start -n "$PKG/.HomeActivity" > /dev/null
shot 22-as-kids 6

# Guest mode on (private rows hidden, a Guest chip at the top) and off again
home guest
shot 23-guest 5
home guest
shot 24-guest-off 4

# Two at once: two of the TV's apps in windows, with the two developer settings on for this
# check only. Reported, not failed: the settings are the viewer's to turn on. Each way of moving
# an app in and out of a window is tried, and where each app ended up is printed.
tasks() {
  adb shell dumpsys activity activities | tr -d '\r' | grep -E '^    \* Task\{' | sed 's/^ *\* //' | head -8
}
clear_tasks() {
  for id in $(adb shell dumpsys activity activities | tr -d '\r' | grep -E '^    \* Task\{' \
      | grep -E "$1" | grep -oE 'StackId=[0-9]+' | cut -d= -f2 | sort -u); do
    adb shell am stack remove "$id" > /dev/null 2>&1 || true
  done
}
print_shot() {
  # The screenshot in the log, small, for reading where the CI files can't be fetched
  [ -f "$OUT/$1.png" ] || return 0
  if command -v ffmpeg > /dev/null && ffmpeg -loglevel error -y -i "$OUT/$1.png" -vf scale=800:-1 -q:v 6 "$OUT/$1-small.jpg"; then
    small="$OUT/$1-small.jpg"
  else
    small="$OUT/$1.png"
  fi
  echo "BEGIN-SHOT $1"; base64 -w 0 "$small"; echo; echo "END-SHOT $1"
}
adb shell settings put global enable_freeform_support 1
adb shell settings put global force_resizable_activities 1
sleep 2
two_apps=$(adb shell cmd package query-activities --brief -a android.intent.action.MAIN -c android.intent.category.LEANBACK_LAUNCHER \
  | tr -d '\r' | grep -oE '[a-zA-Z0-9_.]+/[a-zA-Z0-9_.$]+' | cut -d/ -f1 | grep -v "^$PKG\$" | sort -u | head -2 | paste -sd, -)
echo "Two at once with: $two_apps"
two_up="not tried (fewer than two apps)"
if [ "$(echo "$two_apps" | tr ',' '\n' | grep -c .)" -ge 2 ]; then
  app1=${two_apps%%,*}
  app2=${two_apps##*,}
  pat="$app1|$app2"
  echo "== Two at once, fresh: "; home two_at_once "$two_apps,0"; shot 25-two-at-once 6; tasks; print_shot 25-two-at-once
  if tasks | grep -q "mode=freeform"; then two_up="opened in windows"; else two_up="did not open in windows"; fi
  echo "== Back to one:"; home two_at_once end; sleep 5; tasks
  shot 26-two-at-once-end 2; print_shot 26-two-at-once-end
  if tasks | head -3 | grep "$app1" | grep -q "mode=fullscreen"; then two_up="$two_up; back to one worked"; else two_up="$two_up; back to one did NOT work"; fi
  echo "== The second app opened again from TunaHome:"; home open_app "$app2"; sleep 5; tasks
  if tasks | head -3 | grep "$app2" | grep -q "mode=fullscreen"; then two_up="$two_up; reopening full screen worked"; else two_up="$two_up; reopening full screen did NOT work"; fi
  clear_tasks "$pat"; sleep 2
  echo "== Already open full screen, then Two at once:"
  adb shell monkey -p "$app1" -c android.intent.category.LEANBACK_LAUNCHER 1 > /dev/null 2>&1; sleep 4; tasks
  home two_at_once "$two_apps,0"; sleep 6; tasks
  if tasks | head -3 | grep "$app1" | grep -q "mode=freeform"; then two_up="$two_up; an open app went into a window"; else two_up="$two_up; an open app did NOT go into a window"; fi
  home two_at_once end; sleep 4
  clear_tasks "$pat"
fi
echo "Two at once: $two_up"
adb shell settings put global enable_freeform_support 0
adb shell settings put global force_resizable_activities 0
adb shell am start -n "$PKG/.HomeActivity" > /dev/null
sleep 2

# How quickly TunaHome starts from nothing, before and after the start-up profile is used
start_time() {
  adb shell am force-stop $PKG
  sleep 2
  adb shell am start -W -n "$PKG/.HomeActivity" | tr -d '\r' | awk -F': ' '/TotalTime/ {print $2}'
}
before=$(start_time); sleep 3; before2=$(start_time)
adb shell cmd package compile -m speed-profile -f $PKG > /dev/null 2>&1 || true
sleep 3
after=$(start_time); sleep 3; after2=$(start_time)
echo "Cold start: ${before}ms, ${before2}ms without the profile; ${after}ms, ${after2}ms with it"
sleep 3

# The screen saver, as the TV would start it
adb shell settings put secure screensaver_components "$PKG/.Screensaver" || true
if adb shell am start -n com.android.systemui/.Somnambulator > /dev/null 2>&1; then
  shot 19-screen-saver 8
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
if [ "$phone_ok" = 0 ]; then
  problems=1
fi
if [ "$switched" = 0 ]; then
  problems=1
fi
if [ -z "$(adb shell pidof $PKG | tr -d '\r')" ]; then
  echo "TunaHome isn't running at the end of the check"
  problems=1
fi

if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
  if [ "$problems" = 0 ]; then
    echo "TunaHome ran on an Android TV emulator through every main screen without a problem." >> "$GITHUB_STEP_SUMMARY"
    echo "" >> "$GITHUB_STEP_SUMMARY"
    echo "Two at once on the emulator: $two_up." >> "$GITHUB_STEP_SUMMARY"
    echo "" >> "$GITHUB_STEP_SUMMARY"
    echo "Start from nothing: ${before}ms and ${before2}ms before the start-up profile, ${after}ms and ${after2}ms after." >> "$GITHUB_STEP_SUMMARY"
  else
    echo "TunaHome had a problem on the Android TV emulator: see this job's log and the tv-check files." >> "$GITHUB_STEP_SUMMARY"
  fi
fi
exit "$problems"
