package com.eysnatch.core

/** Everything the user can do with their face. */
enum class Gesture(val label: String, val how: String, val group: String) {
    LOOK_LEFT("Look left", "Keep your head still, glance hard to your left and hold ~half a second", "Eyes"),
    LOOK_RIGHT("Look right", "Keep your head still, glance hard to your right and hold ~half a second", "Eyes"),
    LOOK_UP("Look up", "Keep your head still, look up and hold ~half a second", "Eyes"),
    LOOK_DOWN("Look down", "Keep your head still, look down and hold ~half a second", "Eyes"),
    DOUBLE_BLINK("Double blink", "Two firm blinks in a row", "Eyes"),
    LONG_BLINK("Long blink", "Close both eyes for about a second", "Eyes"),
    EYES_SHUT("Eyes shut 2 s+", "Keep both eyes closed for two seconds or more", "Eyes"),
    WINK_LEFT("Wink left eye", "Close only your left eye for about half a second", "Eyes"),
    WINK_RIGHT("Wink right eye", "Close only your right eye for about half a second", "Eyes"),
    RAISE_BROWS("Raise eyebrows", "Lift both eyebrows and hold ~half a second", "Face"),
    TURN_LEFT("Turn head left", "Turn your head to your left", "Head"),
    TURN_RIGHT("Turn head right", "Turn your head to your right", "Head"),
    TILT_UP("Tilt head up", "Tilt your head up", "Head"),
    TILT_DOWN("Tilt head down", "Tilt your head down", "Head");

    val directional: Boolean
        get() = this == LOOK_LEFT || this == LOOK_RIGHT || this == LOOK_UP || this == LOOK_DOWN ||
            this == TURN_LEFT || this == TURN_RIGHT || this == TILT_UP || this == TILT_DOWN
}

/** Everything EySnatch can do to the phone. */
enum class PhoneAction(val label: String, val repeatable: Boolean = false) {
    NONE("Do nothing"),
    BACK("Back"),
    HOME("Home"),
    RECENTS("Recent apps"),
    SCREENSHOT("Screenshot"),
    SWIPE_LEFT("Swipe left"),
    SWIPE_RIGHT("Swipe right"),
    SCROLL_DOWN("Scroll down", true),
    SCROLL_UP("Scroll up", true),
    FORWARD("Forward / next page"),
    BACKWARD("Backward / previous page"),
    NOTIFICATIONS("Open notifications"),
    QUICK_SETTINGS("Open quick settings"),
    LOCK_SCREEN("Lock screen"),
    PLAY_PAUSE("Play / pause media"),
    VOLUME_UP("Volume up", true),
    VOLUME_DOWN("Volume down", true),
    TOGGLE_PAUSE("Pause / resume EySnatch");
}

object Defaults {
    val mapping: Map<Gesture, PhoneAction> = mapOf(
        Gesture.LOOK_LEFT to PhoneAction.SWIPE_LEFT,
        Gesture.LOOK_RIGHT to PhoneAction.SWIPE_RIGHT,
        Gesture.LOOK_UP to PhoneAction.SCROLL_UP,
        Gesture.LOOK_DOWN to PhoneAction.SCROLL_DOWN,
        Gesture.DOUBLE_BLINK to PhoneAction.BACK,
        Gesture.LONG_BLINK to PhoneAction.HOME,
        Gesture.EYES_SHUT to PhoneAction.TOGGLE_PAUSE,
        Gesture.WINK_LEFT to PhoneAction.SCREENSHOT,
        Gesture.WINK_RIGHT to PhoneAction.RECENTS,
        Gesture.RAISE_BROWS to PhoneAction.FORWARD,
        Gesture.TURN_LEFT to PhoneAction.NONE,
        Gesture.TURN_RIGHT to PhoneAction.NONE,
        Gesture.TILT_UP to PhoneAction.NONE,
        Gesture.TILT_DOWN to PhoneAction.NONE,
    )
}

data class GestureEvent(val gesture: Gesture, val timeMs: Long, val repeat: Boolean = false)
