package dev.worldviewer.selection

/** Keeps asynchronous selection results from being applied to a newer selection. */
class SelectionRequestGate {
    private var version = 0L

    fun selectionChanged() {
        version++
    }

    fun snapshot(): Long = version

    fun isCurrent(snapshot: Long): Boolean = snapshot == version
}
