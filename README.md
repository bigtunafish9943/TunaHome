# TunaHome

A home screen for Android TV: your apps, a row of favourites, and nothing else. Made first for the NVIDIA Shield TV, in the same look as [TunaStream](https://github.com/bigtunafish9943/TunaStream) and [TunaReceive](https://github.com/bigtunafish9943/TunaReceive).

TunaHome works by itself. You don't need TunaStream or TunaReceive to use it.

**Install:** download the APK from the [latest release](https://github.com/bigtunafish9943/TunaHome/releases/latest) and open it on the TV. After that, TunaHome updates itself.

The app id is `com.tunastream.home`.

## What it does

- **Sections of apps across the screen.** Favourites, TV apps and Mobile apps to begin with, plus sections of your own. Each section can show its apps on 1 to 5 lines, or as many lines as it takes so nothing scrolls sideways. Apps fill each line across before the next.
- **Put apps where you want them.** Hold select on an app and choose Move, then use the D-pad to move it (up and down too, in a section with more than one line) and press select to put it down.
- **An app's menu.** Hold select on an app (or press Menu on a remote, or X or Y on a controller) to move it, add it to favourites or one of your sections, change its picture, hide it, open its app info, or uninstall it.
- **Update, TV settings and TunaHome settings** are small buttons beside the clock.
- **Updates itself** from this repo's releases. An update is installed only if it matches the checksum GitHub publishes and is signed with the same key as the TunaHome you have.

## Make it yours

Everything is in TunaHome settings, most used first. Each setting is a short list to pick from.

- **Background:** the theme's colours, your own picture, your own videos and pictures (from the TV or a web address), or a **wallpaper app such as Aerial Views**. Wallpaper apps made for Projectivy Launcher work with TunaHome the same way: pick which videos you want in the wallpaper app's own settings. Also: how much to darken it, how it fits the screen, how often pictures change, shuffled or in order, and the name of what's showing. Videos are silent and stop as soon as you open an app.
- **Sections:** show, hide, rename, reorder and delete sections, add your own, set how many lines of apps each one has, section name style, space between sections, how a section scrolls, and which app you start on.
- **Theme and colours:** nine themes named as in TunaStream (Deep Sea, Reef, Kelp, Midnight, Gold, Halloween, Autumn, Winter, Summer) with seasonal themes, a highlight colour and a text colour.
- **Text:** over 40 fonts (each shown in its own style when you choose), your own .ttf or .otf font file, a separate clock font, six text sizes and a text shadow.
- **App pictures:** TV picture, icon, or icon and name; five sizes; icon packs made for phone launchers; corners from square to fully rounded; card colour; edges; spacing; when names show; and how the app you're on stands out (enlarged, outline colour, dim the rest). Any app can have a picture of your own.
- **Top of the screen:** name and logo, version, clock style and size, date, and which buttons show.

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
