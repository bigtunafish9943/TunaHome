package com.tunastream.home

import android.service.notification.NotificationListenerService

/**
 * Only here so Android will tell TunaHome what's playing: seeing other apps' media needs
 * notification access, which the viewer grants. TunaHome doesn't read or keep notifications.
 */
class NowPlayingListener : NotificationListenerService()
