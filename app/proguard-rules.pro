# TunaHome: what the code shrinker must keep.

# The wallpaper plugin interface: wallpaper apps (Aerial Views) send these across processes,
# and Event finds its subclasses by name
-keep class tv.projectivy.plugin.wallpaperprovider.api.** { *; }

# Android finds these by name from the manifest; listed too so nothing about them is renamed
-keep class com.tunastream.home.HomeButtonService { *; }
-keep class com.tunastream.home.NowPlayingListener { *; }

# Keep line numbers, so Settings > Updates > Last problem points at the right place
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile
