# TunaHome

A home screen for Android TV that you can make entirely your own: your apps in sections, the background you like (Aerial Views included), and every size, colour, glow and spacing tuned to taste. Made first for the NVIDIA Shield TV, for the Shield remote and game controllers, in the same look as [TunaStream](https://github.com/bigtunafish9943/TunaStream) and [TunaReceive](https://github.com/bigtunafish9943/TunaReceive).

TunaHome works by itself. You don't need TunaStream or TunaReceive to use it.

**Install:** download the APK from the [latest release](https://github.com/bigtunafish9943/TunaHome/releases/latest) and open it on the TV. After that, TunaHome updates itself, and shows what changed after each update.

The app id is `com.tunastream.home`.

## Getting started

The first time TunaHome opens, five quick choices set it up: colours, how apps show (TV pictures or icons), the background, the Home button, and bringing back a saved setup. Every step can be skipped, and the welcome can be run again from the bottom of TunaHome settings.

To make the Home button open TunaHome, see [Making the Home button open TunaHome](#making-the-home-button-open-tunahome) below.

## Getting around

- **Sections of apps** run down the screen. Each can show its apps on 1 to 5 lines, or as many lines as it takes so nothing scrolls sideways.
- **The section you're on glides into place**, near the top or in the middle of the screen (or the screen only scrolls when it has to, if you prefer). Moving up or down into a section lands on the app you were on there last.
- **Moving apps:** press select twice quickly on an app (or hold select and choose Move), use the D-pad to move it, and press select to put it down.
- **An app's menu:** hold select on an app (or press Menu on a remote, or X or Y on a controller) to move it, add it to Favourites or a section of your own, put it in a folder, change its icon, rename it, lock it, hide it, open its app info or uninstall it. The same menu opens Quick changes, edits the section, and adds a web link.
- **Side menu:** press Left on the first app of a line. It lists your sections (choose one to jump to it), then search, Quick changes, TunaHome settings, TV settings, network, Bluetooth, the sleep timer, screen saver settings, your saved layouts, installed apps and About this TV. Right or Back closes it. When closed it can show its icons down the left, or nothing.
- **Controller shoulder buttons:** L1 and R1 jump a section up or down, L2 and R2 to the first or last app of the section. Page Up, Page Down, Home and End do the same on a keyboard.
- **Shortcut keys:** double, triple or four presses of a button, or two buttons together, can open an app, jump to a section, search, play or pause, mute, put the TV to sleep, set the sleep timer, open Quick changes and more. Shortcuts on Home and Back work everywhere, not just on the home screen (they need the Home button setting).
- **A pointer for phone apps:** apps made for phones often have buttons the D-pad can't reach. Give *Pointer for phone apps* a shortcut (Back pressed three times, say): the D-pad then moves an arrow over the screen, select taps where it points (hold for a long press), pushing past an edge scrolls, and Back turns it off. It works through the Home button setting, which taps for it; nothing on screen is read.
- **Find my remote:** on a SHIELD, *Find my remote* in the side menu (or as a shortcut, handy on a controller) makes the SHIELD remote beep so you can find it in the sofa. Remotes from 2019 on have the buzzer.
- **Open apps:** press Home twice to see the apps you opened lately, most recent first, over whatever is on screen. Select one to switch back to it; hold select (or press Menu) to close it, or close them all. Android doesn't let other apps see what's running, so the list is what came to the front, through the Home button setting. The shortcut can be changed or removed under Shortcut keys.
- **Search** with the keyboard or your voice, across apps, web links, Watch Next and PC games, with a way to search the rest of the TV.
- **Up next** (Sections settings): the PC game you've played in the last two weeks joins the Watch Next row, everything in the order you left it, so the game you're in the middle of sits beside your shows.
- **Voice commands** in voice search: "open Netflix", "watch Stranger Things" (from Watch Next or PC games, or else the TV's own search), "watch The Office on Netflix" (searches inside the app, where the app allows it), "find …", "go to Favourites", "sleep in 30 minutes", "cancel the sleep timer", "next background", "settings". The remote's voice button on the home screen can open these instead of the TV's assistant. With *The voice button in other apps* on (and the Home button setting), the remote's voice button opens TunaHome's voice commands from inside any app too: "open Netflix" or "play Halo" without going home first.
- **Top of the screen:** the TunaHome name and logo, the clock (as numbers, with hands, or both), the date, the weather, free storage and memory, the remote's and Bluetooth controllers' batteries (when low, or always), network and VPN icons, and buttons for search, updates, TV settings and TunaHome settings. Each can be shown or hidden.
- **Holidays and your dates** (Background settings): on New Year, Valentine's Day, Halloween, Thanksgiving and Christmas, and on birthdays and dates you add, a greeting with a short chime the first time the home screen comes up, and an effect all day (confetti, hearts, bats, leaves, snow).
- **Dim at night** (Top of the screen settings): from 9, 10 or 11 p.m. the home screen darkens and warms a little over half an hour, like a phone's night light, until 6 in the morning. Apps and games aren't touched.
- **Morning screen:** the first time the home screen comes up each morning, a big clock, the date, today's weather, what you were in the middle of in Watch Next and yesterday's time on the TV, then any button goes on to your apps. Top of the screen settings turn it off.
- **Idle mode:** after a few minutes with no button pressed, everything but the background (and the clock, if you like) fades away. Any button brings it back.
- **Sleep timer:** the TV goes to sleep after 15 minutes to 3 hours. A minute before, a message says so and the sound fades down; any button then keeps watching for 15 more minutes. The volume is back to normal next time. The time left shows beside the clock. Needs the Home button setting.
- **Screen saver:** TunaHome can be the TV's screen saver, showing your background (Aerial Views too) with the time, the weather and what's playing, moving corner to corner every few minutes. Choose it in the TV's screen saver settings (TunaHome settings, Background, Screen saver, takes you there).
- **Your week on the TV** (TunaHome settings): how long each app was on screen this week, day by day, with the apps used most. Timed through the Home button setting while the screen is on, kept on the TV for two weeks and never sent anywhere; *Clear* forgets it.
- **Messages from your PC** (TunaHome settings): link [TunaStream](https://github.com/bigtunafish9943/TunaStream) (TunaHome finds the PC, you select *Allow* on TunaStream's home page) and messages it sends to everyone pop up at the top of the TV with a chime, over whatever is on, streaming or not. TunaStream's *Goodnight* puts the TV to sleep. The link can only read messages; it works while the Home button setting is on, and stops when the TV sleeps.
- **Picture test** (TunaHome settings): test patterns for setting up the TV's picture (black and white level, colour bars, gradients for banding, edges for overscan and sharpness, and a moving bar for smooth motion that counts frames the TV box skips), with the screen mode the box is sending, the other refresh rates the TV offers and its HDR formats. Left and Right change pattern.

## Sections

Favourites, TV apps and Mobile apps are there to begin with. These can be switched on, and all of them can be renamed, reordered, hidden or given their own look:

- **For you:** the apps you usually open at this time of day, learnt as you use TunaHome.
- **Recently opened**, newest first.
- **Watch Next:** what you were watching, with how far through you are, kept up to date as apps change it. Rest on a show and its **preview** plays silently behind the apps, where its app gives one. Hold select on a show to leave its app out of Watch Next (and bring every app back the same way). TunaHome asks once to read *TV listings*, which Android needs before it shares other apps' shows with a launcher; if it was turned down, *Watch Next from your apps* in Sections settings asks again. Apps decide what they share: some share nothing outside Google's own home screen.
- **PC games** from TunaReceive (see [With TunaReceive](#with-tunareceive)).
- **Now playing:** what's playing, with its picture and controls. Needs notification access (TunaHome settings explains how); TunaHome uses it only to see what's playing, and doesn't read or keep notifications.
- **Sideloaded apps:** apps that didn't come from the Play Store.
- **Games, Video and TV, and Music and audio:** filled automatically from what each app says it is, or else from its name.
- **Sections of your own**, with any apps and **web links** in them. A link (a website, a YouTube channel, a Plex library, any app link) shows the picture of the app that opens it, or one you choose.
- **Folders**, if you want them, gather several apps into one card.

Each section can have its own number of lines, card size and card style (TV pictures in one, icons in another), and its own order: your own, A to Z, most used, newest installed or recently updated. **Spotlight** shows the first app of a one-line section twice the size. Apps installed in the last three days show **"New"** until you open them.

## Make it yours

Everything is in TunaHome settings, the most used first. **Search settings** at the top finds any setting by name. **Quick changes** (in the side menu, an app's menu, or as a shortcut) puts the most changed settings over the home screen itself, so you see each change straight away.

- **Fine tuning:** card size, corners, space between cards, how much the app you're on grows, its glow, the shine, movement speed, how much the other apps dim, space between sections, text size, clock size, background darkness and the panel behind each section, each adjusted step by step with Left and Right while a row of your own apps shows the result live above. Select a row to put it back to its usual value. **Ready-made styles** (Calm, Classic, Bold, Neon, Cinema) set the focus effects together, ready to tune further.
- **Theme and colours:** nine themes named as in TunaStream (Deep Sea, Reef, Kelp, Midnight, Gold, Halloween, Autumn, Winter, Summer), with seasonal themes that switch by themselves, a highlight colour and a text colour.
- **Text:** over 40 fonts (each shown in its own style when you choose), your own .ttf or .otf font file, a separate clock font, text sizes and a text shadow.
- **Cards and icons:** TV pictures, the apps' own icons, or any icon pack; icons edge to edge or smaller inside the card; the name inside the card or under it, always, only on the app you're on, or never; long names scroll along; card shape, colour, edges and corners; every icon in one colour (white or the highlight colour); any app given an icon of your own (from the TV or a web address).
- **The app you're on:** enlarged, with an outline, a glow or a lift (in the highlight colour, white, or the app's own colour), a soft shine sweeping across, the rest dimmed, and smooth, bouncy, quick or instant movement.
- **Sections:** a dark glass, tinted or outlined panel behind each one; section name style; space between sections; how a section scrolls; which app you start on.
- **Sounds:** optional click sounds as you move and select, in several sets.

Choosing a file needs an app on the TV that can browse files, such as a file manager.

### Backgrounds

- The theme's colours, your own picture, your own videos and pictures (from the TV or a web address), a picture of the day (Bing, Wikipedia or NASA), or a **wallpaper app such as Aerial Views**. Wallpaper apps made for Projectivy Launcher work with TunaHome the same way: pick which videos you want in the wallpaper app's own settings.
- **A photo frame:** on TunaHome's phone page, send any number of photos and videos from your phone; they show behind your apps in turn and on TunaHome's screen saver, each picture slowly zooming in or out (*Slow zoom on pictures*).
- How dark it is, how it fits the screen, how often pictures change, shuffled or in order, and the name of what's showing.
- Brightness, contrast, colour strength and blur. Animated GIF and WebP pictures play (Android 9 and later).
- The background can **follow the app you're on** (its TV picture fades in, sharp or blurred) and **drift** a little as you scroll.
- Videos are silent and stop as soon as you open an app.

### Moving effects

Snow, rain, a storm with lightning, autumn leaves, bats, fireflies, stars (with the odd shooting star) or fog, drifting behind your apps. They can **follow the weather** outside (rain when it's raining, stars on a clear night), **follow the season** (snow in the Winter theme, leaves in Autumn, bats at Halloween, fireflies in Summer), either of those, or stay as you choose. How many is adjustable, and they can show on the screen saver too. Off to begin with.

### Icon packs

Icon packs made for phone launchers work, including those already on the TV. TunaHome can also download free, open-source packs from [F-Droid](https://f-droid.org) and [IzzyOnDroid](https://apt.izzysoft.de/fdroid/) and install them for you (Android asks you to confirm). Apps a pack doesn't cover can keep their own icon, be framed to match, or be drawn as white or highlight-coloured line art.

## With TunaReceive

With [TunaReceive](https://github.com/bigtunafish9943/TunaReceive) on the TV:

- **PC games** shows your PC's games with box art, most recently played first; select one to start it. Games hidden in TunaReceive stay hidden, and locking TunaReceive in TunaHome's app lock locks its games too. Open TunaReceive once so it lists a PC's games. Needs a TunaReceive that shares its games list; without TunaReceive the section simply doesn't show.
- **While a game streams**, TunaHome stops looking at button presses, so controller input goes straight to the game with no added delay (see below).
- TunaReceive can be the app that **opens when the TV wakes up**.

## Set up from your phone

Typing on a TV is slow, so TunaHome can be set up from a phone on the same Wi-Fi. Open **Set up from your phone** (side menu, TunaHome settings, or a shortcut): the TV shows an address, a six-digit code and a QR code. Point the phone's camera at the QR code and the page opens already signed in, or open the address and type the code. Then you can:

- drag apps into order in any section (the arrows still work too), move apps between Favourites and your own sections, and show, hide and rename sections;
- rename, hide or favourite any app, add it to a section of your own, and send a picture from the phone to use as its card;
- set up Who's watching: add people, rename them, choose their colour, give each a photo, or remove them (a person with a PIN needs it typed first; PINs themselves are set on the TV);
- add web links;
- send pictures and videos from the phone as the background;
- change the theme, highlight colour, cards, effects and more.
- **Report a problem**: get what TunaHome sees on the TV right now (its version, the controllers and USB devices plugged in, the last problem it hit and its own log) to copy into a message.

The changes show on the TV when you close its setup screen. The page only works while that screen is open, only from your home network, and only after the code is typed; five wrong codes make a new one.

## Who's watching

Each person can have their own TunaHome: sections, favourites, look, background, app lock and what For you learns. Set it up in TunaHome settings, **Who's watching**: name yourself and the next person, who starts as a copy of your setup or fresh. Anyone can have a PIN, so children can't switch into a grown-up's TunaHome or change it. Each person can have a photo, sent from a phone, in place of the coloured circle. "Who's watching?" can appear when the TV wakes up or when TunaHome starts, and is always in the side menu.

- **Guest mode** (side menu, or a shortcut): a clean home screen for visitors. Watch Next, recently used, now playing, For you and PC games rows are out of sight, Open apps is empty and nothing new is remembered there, and nobody is asked who's watching. *Guest* shows at the top; it ends when the TV sleeps, or from the side menu (with the settings PIN if there is one).
- **Kids mode** (a person's options in Who's watching): only the apps a grown-up chose show and open; any other app that comes up, however it was opened, gives way to a *Not in kids mode* screen where a grown-up's PIN can allow it. TunaHome settings and app menus ask a grown-up's PIN too. It needs a grown-up with a PIN, so it can't simply be left.
- **Daily limits:** a daily time for a person (all their apps), and for any app (hold it, *Daily limit*; everyone's time in it counts). Five minutes before, a message warns; at the limit the app gives way to *Time's up*, where a grown-up's PIN gives 15, 30 or 60 more minutes for today. Time is counted while apps are on screen, through the Home button setting.

## Looking after your setup

- **Layouts:** save your whole setup under a name and switch between setups (from settings or the side menu), say one for the children and one for you. Export a layout to Downloads, or bring one back from a file.
- **Automatic backup** of your setup to Downloads once a day, the last three kept, ready to bring back from Layouts.
- **App lock:** a PIN on chosen apps and, if you like, on TunaHome settings, plus **locked hours** (bedtime, say) when every app needs it. It's a child lock, not security: it only stops apps being opened from TunaHome.
- **Starts instantly after a restart:** a copy of your apps and their pictures is kept on the TV and shown at once while the full list loads.
- **Updates itself** from this repo's releases. An update is installed only if it matches the checksum GitHub publishes and is signed with the same key as the TunaHome you have.
- **If something goes wrong,** TunaHome keeps a note of the last problem (TunaHome settings, Updates, Last problem), so it can be passed on without a computer. Nothing is sent anywhere.

## Making the Home button open TunaHome

Android TV has no setting for choosing your home screen, so the Home button keeps opening the TV's own one. TunaHome can step in front of it with an accessibility service that you switch on yourself:

1. Open **TunaHome settings**, then **Home button**, then **Open accessibility settings**.
2. Choose **TunaHome** and switch it on.

If another launcher (such as Projectivy) already takes over the Home button, switch that off first. Only one app should catch the Home button at a time.

**Or make it the TV's default home screen.** TunaHome tells Android it is a home screen, so launcher managers such as TV Box Tools' **Launcher Manager** list it and can set it as the default, and on TVs that ask which home screen to use, TunaHome is one of the choices. With TunaHome as the default you don't need the accessibility service for the Home button, though shortcuts on Home and Back, the sleep timer and waking into an app still need it.

**To undo it,** switch TunaHome off on the same accessibility screen. The Home button goes back to the TV's own home screen straight away. Nothing on the TV is disabled or removed by this.

What the service does:

- A Home press opens TunaHome.
- If you turn on **Replace the usual home screen** in TunaHome settings, TunaHome also opens whenever the TV's own home screen appears, such as after Back out of an app or a restart. This is off to begin with.
- It runs shortcuts set on Home and Back, the sleep timer and the Sleep shortcut, and opens your chosen app when the TV wakes up.
- While you have the pointer for phone apps on, it moves the arrow with the D-pad and taps where it points.

It only receives key presses and the name of the app that came to the front, and taps only for the pointer. It cannot read what is on screen, and it sends nothing anywhere. If the pointer says it can't tap, switch TunaHome off and on once on that accessibility screen so Android lets it.

**While TunaReceive streams a game**, the service stops looking at button presses, so controller input goes straight to the game with no added delay. Home then opens the TV's own home screen (or TunaHome, if "Replace the usual home screen" is on), and shortcuts on Home and Back wait until the stream ends.

**Safety net:** if TunaHome stops with an error twice within three minutes, the Home button lets the TV's own home screen open for a few minutes, so the TV is never left without one.

Known limits: holding Home no longer reaches the TV's own long-press action while the service is on, and a TV update can stop this kind of override working.

## What TunaHome sends over the internet

Nothing about you or your TV is collected. TunaHome only connects to:

- **GitHub**, to check for updates, download them and show what changed.
- **Open-Meteo**, for the weather at the place you chose. If you ask TunaHome to find your place from the TV's internet address, **ipapi.co** is asked once.
- **Bing, Wikipedia or NASA**, for the picture of the day, if you chose that background.
- **F-Droid or IzzyOnDroid**, when you download an icon pack.
- Web addresses you give it yourself, for pictures, videos and icons.

**Set up from your phone** runs a small web page on the TV only while its screen is open, answering only on your home network and only after the code on the TV is typed.

For You, Recently opened, app use counts and everything else stay on the TV.

## Building

GitHub Actions builds every push. Pushes to the default branch publish a release `v0.1.<run number>` with the APK attached, signed from the repo secrets `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS` and `KEY_PASSWORD`. Without those secrets the build still runs, but the APK is signed with a throwaway key and no release is published. Before a release goes out, each build is run on an Android TV emulator: it goes through the main screens with remote button presses, takes a screenshot of each (kept with the build for 30 days), and stops the release if TunaHome stopped with an error or stopped answering. It also tries the snow, leaves, rain and storm effects, sets up Who's watching and switches person, checks that the phone setup page answers, and measures how quickly TunaHome starts from nothing. The screenshots are attached to each release.

TunaHome comes with a start-up profile (`app/src/main/baseline-prof.txt`), so Android compiles its code ahead of time when it's installed and the home screen comes up sooner after a restart. It's written by hand to cover all of TunaHome's own code, rather than recorded on a device. Release notes are made from the commit messages since the last release.

To build it yourself you need Java 17 and the Android SDK (platform 36):

```
./gradlew :app:assembleRelease
```

## Credits and licence

TunaHome is written from scratch; it is not based on another launcher. It builds on:

- [TunaReceive](https://github.com/bigtunafish9943/TunaReceive) (GPL-3.0, same author): the themes, the fish mark and the self-updater follow it.
- [Projectivy Launcher's wallpaper plugin interface](https://github.com/spocky/projectivy-plugin-wallpaper-provider) (Apache 2.0), copied unchanged into `projectivyapi/`, so wallpaper apps such as Aerial Views can show their videos behind TunaHome.
- AndroidX RecyclerView, AndroidX Media3, AndroidX ProfileInstaller and the Kotlin standard library (Apache 2.0).
- [ZXing](https://github.com/zxing/zxing) core (Apache 2.0), to draw the QR code on the phone setup screen.
- [Google Fonts](https://github.com/google/fonts) (SIL Open Font License 1.1), trimmed to Latin characters; each font's licence is beside it in `app/src/main/assets/fonts/licences`.
- Google's [Material icons and Material Symbols](https://github.com/google/material-design-icons) (Apache 2.0) for the small symbols; the weather icons are drawn from Material shapes.
- Data and pictures from [Open-Meteo](https://open-meteo.com) (CC BY 4.0), Bing, Wikipedia and NASA, shown with their names.

GPL-3.0. See [LICENSE.txt](LICENSE.txt) and [NOTICE](NOTICE).
