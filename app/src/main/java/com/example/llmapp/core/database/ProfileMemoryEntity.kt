package com.example.llmapp.core.database

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Profile memory: key-value user identity and preferences.
 * Supports temporal versioning via validFrom/validTo.
 */
@Entity(tableName = "profile_memories")
data class ProfileMemoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val key: String,                 // "name", "location", "pet_name", "preference_language"
    val value: String,               // "Ashish", "Mumbai", "Luna"
    val importanceScore: Float = 0.5f,
    val epistemicState: String = "ASSUMED", // VERIFIED, PROBABLE, ASSUMED, CONTRADICTED
    val validFrom: Long = System.currentTimeMillis(),
    val validTo: Long? = null,       // null = currently active
    val accessCount: Int = 1,
    val lastAccessed: Long = System.currentTimeMillis(),
    val lineageId: String? = null    // Session that created/inferred this
)
