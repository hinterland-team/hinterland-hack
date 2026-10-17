package com.hinterland.clinic

// lines for the dashboard's live feed (docs/journeys.md 5). otto's lines go as said; the patient only as
// the understood intent, unless the dashboard said showPatientText=true. plain kotlin, unit tested.
class FeedBuffer(private val capacity: Int = CAPACITY) {

    private val items = ArrayDeque<FeedItem>()
    private var lastSaid = ""
    private var lastSaidAt = 0L

    // what the last feed answer said; off until the dashboard turns it on
    @Volatile var showPatientText = false

    // a ready line otto says himself (v3.3) comes back once more from the speech listener, maybe in parts:
    // the same words within SAME_LINE_MS count once
    @Synchronized
    fun ottoSaid(at: Long, text: String): Boolean {
        val key = text.lowercase().filter { it.isLetterOrDigit() }
        if (key.isNotEmpty() && at - lastSaidAt in 0..SAME_LINE_MS && lastSaid.contains(key)) return false
        if (!add(FeedItem(at, FeedItem.OTTO, FeedItem.SAID, text))) return false
        lastSaid = key
        lastSaidAt = at
        return true
    }

    @Synchronized
    fun ottoDid(at: Long, text: String) = add(FeedItem(at, FeedItem.OTTO, FeedItem.ACTION, text))

    @Synchronized
    fun patientIntent(at: Long, text: String) = add(FeedItem(at, FeedItem.PATIENT, FeedItem.INTENT, text))

    // raw words only with the demo switch on, otherwise they never leave the robot
    @Synchronized
    fun patientSaid(at: Long, text: String): Boolean {
        if (!showPatientText) return false
        return add(FeedItem(at, FeedItem.PATIENT, FeedItem.SAID, text))
    }

    @Synchronized
    fun drain(max: Int = BATCH): List<FeedItem> {
        val out = ArrayList<FeedItem>(minOf(max, items.size))
        while (out.size < max && items.isNotEmpty()) out.add(items.removeFirst())
        return out
    }

    // a failed post: the lines go back to the front, the oldest are dropped when full
    @Synchronized
    fun putBack(batch: List<FeedItem>) {
        batch.asReversed().forEach { items.addFirst(it) }
        while (items.size > capacity) items.removeFirst()
    }

    @Synchronized
    fun size() = items.size

    private fun add(item: FeedItem): Boolean {
        val text = item.text.trim()
        if (text.isEmpty()) return false
        items.addLast(item.copy(text = text.take(MAX_TEXT)))
        while (items.size > capacity) items.removeFirst()
        return true
    }

    companion object {
        const val CAPACITY = 100
        const val BATCH = 20
        const val MAX_TEXT = 300
        const val SAME_LINE_MS = 10_000L
    }
}
