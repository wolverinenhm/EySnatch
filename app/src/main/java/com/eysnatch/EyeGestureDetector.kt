package com.eysnatch

enum class EyeAction { SWIPE_LEFT, SWIPE_RIGHT, SCROLL_UP, SCROLL_DOWN, BACK, HOME, SCREENSHOT, RECENTS }

/** Turns per-frame face measurements into discrete actions. */
class EyeGestureDetector {
    private val closedProb = 0.25f
    private val openProb = 0.6f
    private val turnDeg = 20f
    private val neutralDeg = 10f
    private val pitchDeg = 15f
    private val cooldownMs = 800L

    private var bothClosedSince = 0L
    private var lastBlinkAt = 0L
    private var winkSince = 0L
    private var winkSide = 0 // -1 left, 1 right
    private var winkFired = false
    private var headArmed = true
    private var lastActionAt = 0L

    fun process(leftOpen: Float, rightOpen: Float, yaw: Float, pitch: Float, now: Long): EyeAction? {
        val action = detect(leftOpen, rightOpen, yaw, pitch, now) ?: return null
        if (now - lastActionAt < cooldownMs) return null
        lastActionAt = now
        return action
    }

    private fun detect(l: Float, r: Float, yaw: Float, pitch: Float, now: Long): EyeAction? {
        val bothClosed = l < closedProb && r < closedProb
        val bothOpen = l > openProb && r > openProb

        if (bothClosed) {
            if (bothClosedSince == 0L) bothClosedSince = now
            winkSince = 0L
            return null
        }
        if (bothClosedSince != 0L && bothOpen) {
            val d = now - bothClosedSince
            bothClosedSince = 0L
            if (d >= 1000) return EyeAction.HOME
            if (d >= 60) {
                val double = now - lastBlinkAt < 700
                lastBlinkAt = if (double) 0L else now
                if (double) return EyeAction.BACK
            }
            return null
        }

        // Winks: one eye closed, the other clearly open, held ~350 ms
        val side = when {
            l < closedProb && r > openProb -> -1
            r < closedProb && l > openProb -> 1
            else -> 0
        }
        if (side != 0) {
            if (side != winkSide || winkSince == 0L) { winkSide = side; winkSince = now; winkFired = false }
            if (!winkFired && now - winkSince >= 350) {
                winkFired = true
                return if (side == -1) EyeAction.SCREENSHOT else EyeAction.RECENTS
            }
            return null
        }
        winkSince = 0L
        winkFired = false

        // Head movement, only when eyes are open; must return to centre before re-triggering
        if (bothOpen) {
            if (kotlin.math.abs(yaw) < neutralDeg && kotlin.math.abs(pitch) < neutralDeg) headArmed = true
            if (headArmed) {
                val a = when {
                    yaw > turnDeg -> EyeAction.SWIPE_LEFT
                    yaw < -turnDeg -> EyeAction.SWIPE_RIGHT
                    pitch > pitchDeg -> EyeAction.SCROLL_UP
                    pitch < -pitchDeg -> EyeAction.SCROLL_DOWN
                    else -> null
                }
                if (a != null) { headArmed = false; return a }
            }
        }
        return null
    }
}
