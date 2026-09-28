package com.example.voicebrainlive.desktop.core

import java.util.UUID

/**
 * Memory Category representing different types of long-term knowledge stored in the AI Brain.
 */
enum class MemoryCategory(val displayName: String, val colorHex: String, val icon: String) {
    FACT("အချက်အလက် (Fact)", "#00E5FF", "📌"),
    PREFERENCE("အကြိုက်/စိတ်ကြိုက် (Preference)", "#FFAB00", "⭐"),
    HABIT("အလေ့အထ (Habit)", "#E040FB", "🧠"),
    PROJECT("ပရောဂျက် (Project)", "#00E676", "💻"),
    ROUTINE("အစီအစဉ် (Routine)", "#2979FF", "⚡")
}

/**
 * Unified Memory Node item stored persistently in Nilar AI's neural brain memory store.
 */
data class UnifiedMemoryItem(
    val id: String = UUID.randomUUID().toString(),
    val category: MemoryCategory,
    val title: String,
    val content: String,
    val timestamp: Long = System.currentTimeMillis(),
    val confidencePercent: Int = 100,
    val source: String = "manual",
    val requiresConfirmation: Boolean = false,
    val rawKey: String? = null,
    val isEditable: Boolean = true
)
