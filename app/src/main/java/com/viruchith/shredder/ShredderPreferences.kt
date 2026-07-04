package com.viruchith.shredder

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit

/**
 * ShredderPreferences manages storage and retrieval of application-specific configurations,
 * such as the selected shredding algorithm and customized buffer sizes.
 */
class ShredderPreferences private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("shredder_prefs", Context.MODE_PRIVATE)

    fun getSelectedAlgorithm(): ShredAlgorithm {
        val name = prefs.getString("selected_algorithm", ShredAlgorithm.Standard.name) ?: ShredAlgorithm.Standard.name
        return ShredAlgorithm.fromName(name)
    }

    fun setSelectedAlgorithm(algorithm: ShredAlgorithm) {
        prefs.edit {
            putString("selected_algorithm", algorithm.name)
        }
    }

    fun getBufferSize(): Int {
        return prefs.getInt("buffer_size", 1024 * 1024) // Default 1MB buffer
    }

    fun setBufferSize(size: Int) {
        prefs.edit {
            putInt("buffer_size", size)
        }
    }

    companion object {
        @Volatile
        private var INSTANCE: ShredderPreferences? = null

        fun getInstance(context: Context): ShredderPreferences {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ShredderPreferences(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
