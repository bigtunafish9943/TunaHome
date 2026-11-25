# TunaHome

A home screen for Android TV: your apps, a row of favourites, and nothing else. Made first for the NVIDIA Shield TV, in the same look as [TunaStream](https://github.com/bigtunafish9943/TunaStream) and [TunaReceive](https://github.com/bigtunafish9943/TunaReceive).

TunaHome works by itself. You don't need TunaStream or TunaReceive to use it.

**Install:** download the APK from the [latest release](https://github.com/bigtunafish9943/TunaHome/releases/latest) and open it on the TV. After that, TunaHome updates itself.

The app id is `com.tunastream.home`.

## What it does

- **Rows of apps that run the width of the screen.** Favourites first, in the order you choose, then apps made for TV, then mobile apps. The app you are on is enlarged and its name appears under it.
- **An app's menu.** Hold select on an app (or press Menu on a remote, or X or Y on a controller) to add it to favourites, move it, change its picture, hide it, open its app info, or uninstall it.
- **Update, TV settings and TunaHome settings** are small buttons beside the clock.
- **Make it yours**, in TunaHome settings:
  - **Wallpaper:** any picture on the TV, behind the home screen. Without one, the theme's colours show.
  - **Theme:** nine colour themes, named as in TunaStream: Deep Sea, Reef, Kelp, Midnight, Gold, Halloween, Autumn, Winter and Summer. With seasonal themes on, Halloween shows in October, Autumn in November, Winter from December to 15 January, and Summer from 21 June to the end of August.
  - **Text style and text size.**
  - **App picture size**, and your own picture for any app from its menu.
- **Remote and controller.** Everything works with the D-pad and select. Back returns to the top of the home screen.
- **Updates itself** from this repo's releases, from the Update button or when the home screen opens. An update is installed only if it matches the checksum GitHub publishes and is signed with the same key as the TunaHome you have.

Choosing a picture needs an app on the TV that can browse files, such as a file manager.

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
