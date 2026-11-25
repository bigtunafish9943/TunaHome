# TunaHome

A home screen for Android TV: your apps, a row of favourites, and nothing else. Made first for the NVIDIA Shield TV, in the same look as [TunaStream](https://github.com/bigtunafish9943/TunaStream) and [TunaReceive](https://github.com/bigtunafish9943/TunaReceive).

TunaHome works by itself. You don't need TunaStream or TunaReceive to use it.

**Install:** download the APK from the [latest release](https://github.com/bigtunafish9943/TunaHome/releases/latest) and open it on the TV. After that, TunaHome updates itself.

The app id is `com.tunastream.home`.

## What it does

- **Sections of apps across the screen.** Favourites, TV apps and Mobile apps to begin with, plus sections of your own. Each section can show its apps on 1 to 5 lines, or as many lines as it takes so nothing scrolls sideways. Apps fill each line across before the next.
- **Put apps where you want them.** Press select twice quickly on an app (or hold select and choose Move), then use the D-pad to move it (up and down too, in a section with more than one line) and press select to put it down.
- **Side menu.** Press Left on the first app of a line to open a menu of your sections (choose one to jump to it) and shortcuts to search, TunaHome settings, TV settings, network, Bluetooth, screen saver, your saved layouts, installed apps and About this TV. Right or Back closes it. When closed it can show its icons down the left, or nothing.
- **An app's menu.** Hold select on an app (or press Menu on a remote, or X or Y on a controller) to move it, add it to favourites or one of your sections, change its icon, hide it, open its app info, or uninstall it.
- **Update, TV settings and TunaHome settings** are small buttons beside the clock.
- **Updates itself** from this repo's releases. An update is installed only if it matches the checksum GitHub publishes and is signed with the same key as the TunaHome you have.

- **Idle mode.** After a few minutes with no button pressed, everything but the background (and the clock, if you like) fades away. Any button brings it back.
- **Recently opened** section, newest first (switch it on in Sections), and a **network icon** beside the clock.
- **App lock.** A PIN on chosen apps and, if you like, on TunaHome settings. It's a child lock, not security: it only stops apps being opened from TunaHome.
- **Open an app when the TV wakes up**, such as TunaReceive. Needs the Home button setting (below), because Android only lets that kind of app open things by itself.
- **Free icon packs** from F-Droid, downloaded and installed from inside TunaHome (Android asks you to confirm).
- **Watch Next, Now playing, weather, search with voice, folders and shortcut keys** (double, triple or four presses, or two buttons together).
- **Rename any app** from its menu, and **sort any section** your own way, A to Z, most used, newest installed or recently updated.
- **Spotlight:** the first app in a one-line section can be shown twice the size.
- **Sideloaded apps, Games, Video and TV, and Music and audio** sections (hidden to begin with), filled automatically from what each app says it is, or else its name.
- **The section you're on glides into place**, near the top or in the middle of the screen, as you move up and down.
- **"New"** on apps installed in the last three days until you open them.
- **Screen saver.** TunaHome can be the TV's screen saver: your background (Aerial Views too) with the time, moving corner to corner every few minutes. Choose it in the TV's screen saver settings.
- **Controller shoulder buttons:** L1 and R1 jump a section up or down, L2 and R2 to the first or last app.
- **A shine** sweeps across the card you move onto, and long names scroll along.
- **PC games** from [TunaReceive](https://github.com/bigtunafish9943/TunaReceive), with box art, most recently played first; select one to start it. Needs a TunaReceive that shares its games list; without TunaReceive the section simply doesn't show.
- **Goes back to where you were:** moving up or down into a section lands on the app you were on there last.

## Make it yours

Everything is in TunaHome settings, most used first. Each setting is a short list to pick from.

- **Background:** the theme's colours, your own picture, your own videos and pictures (from the TV or a web address), or a **wallpaper app such as Aerial Views**. Wallpaper apps made for Projectivy Launcher work with TunaHome the same way: pick which videos you want in the wallpaper app's own settings. Also: how much to darken it, how it fits the screen, how often pictures change, shuffled or in order, and the name of what's showing. Videos are silent and stop as soon as you open an app. The background can follow the app you're on (its TV picture fades in, sharp or blurred) and drift a little as you scroll. Brightness, contrast, colour strength and blur can be changed, and animated GIF and WebP pictures play (Android 9 and later).
- **Sections:** show, hide, rename, reorder and delete sections, add your own, set how many lines of apps each one has, section name style, space between sections, how a section scrolls, and which app you start on.
- **Theme and colours:** nine themes named as in TunaStream (Deep Sea, Reef, Kelp, Midnight, Gold, Halloween, Autumn, Winter, Summer) with seasonal themes, a highlight colour and a text colour.
- **Text:** over 40 fonts (each shown in its own style when you choose), your own .ttf or .otf font file, a separate clock font, six text sizes and a text shadow.
- **Icons:** one list to pick from: TV pictures, the apps' own icons, or any icon pack on the TV (plus more to download); icons fill the whole card edge to edge, or sit smaller inside it; the name inside the card if you like; five sizes; icon packs made for phone launchers; every icon in one colour (white or the highlight colour); corners from square to fully rounded; card colour; edges; spacing; when names show; and how the app you're on stands out (enlarged, a glow or outline in the app's own colour, dim the rest). Any app can have an icon of your own (from the TV or a web address).
- **Top of the screen:** name and logo, version, clock style and size, date, free storage and memory, and which buttons show.

Choosing a file needs an app on the TV that can browse files, such as a file manager.

## Making the Home button open TunaHome

Android TV has no setting for choosing your home screen, so the Home button keeps opening the TV's own one. TunaHome can step in front of it with an accessibility service that you switch on yourself:

1. Open **TunaHome settings**, then **Home button**, then **Open accessibility settings**.
2. Choose **TunaHome** and switch it on.

If another launcher (such as Projectivy) already takes over the Home button, switch that off first. Only one app should catch the Home button at a time.

**To undo it,** switch TunaHome off on the same accessibility screen. The Home button goes back to the TV's own home screen straight away. Nothing on the TV is disabled or removed by this.

What the service does:

- A Home press opens TunaHome.
- If you turn on **Replace the usual home screen** in TunaHome settings, TunaHome also opens whenever the TV's own home screen appears, such as after Back out of an app or a restart. This is off to begin with.

It only receives key presses and the name of the app that came to the front. It cannot read what is on screen, and it sends nothing anywhere.

Known limits: holding Home no longer reaches the TV's own long-press action while the service is on, and a TV update can stop this kind of override working.

## Building

GitHub Actions builds every push. Pushes to the default branch publish a release `v0.1.<run number>` with the APK attached, signed from the repo secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Without those secrets the build still runs, but the APK is signed with a throwaway key and no release is published.

To build it yourself you need Java 17 and the Android SDK (platform 36):

```
./gradlew :app:assembleRelease
```

## Credits and licence

TunaHome is written from scratch; it is not based on another launcher. Its themes, branding and self-updater follow [TunaReceive](https://github.com/bigtunafish9943/TunaReceive). It uses AndroidX RecyclerView and the Kotlin standard library (both Apache 2.0).

GPL-3.0. See [LICENSE.txt](LICENSE.txt) and [NOTICE](NOTICE).
