package com.unremarkable.android.storage

import android.content.Context
import com.unremarkable.core.model.DeviceProfile

enum class ThemeMode {
    SYSTEM, DARK, LIGHT
}

enum class PageTurnMode {
    SWIPE, TAP, BOTH
}

class AppSettings(context: Context) {
    private val prefs = context.getSharedPreferences("rm_app_settings", Context.MODE_PRIVATE)

    var pageTurnMode: PageTurnMode
        get() {
            val name = prefs.getString("page_turn_mode", PageTurnMode.SWIPE.name) ?: PageTurnMode.SWIPE.name
            return try {
                PageTurnMode.valueOf(name)
            } catch (_: Exception) {
                PageTurnMode.SWIPE
            }
        }
        set(value) {
            prefs.edit().putString("page_turn_mode", value.name).apply()
        }

    var themeMode: ThemeMode
        get() {
            val name = prefs.getString("theme_mode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name
            return try {
                ThemeMode.valueOf(name)
            } catch (e: Exception) {
                ThemeMode.SYSTEM
            }
        }
        set(value) {
            prefs.edit().putString("theme_mode", value.name).apply()
        }

    var defaultDeviceProfile: DeviceProfile
        get() {
            val id = prefs.getString("device_profile", DeviceProfile.PAPER_PRO.id) ?: DeviceProfile.PAPER_PRO.id
            return DeviceProfile.fromId(id)
        }
        set(value) {
            prefs.edit().putString("device_profile", value.id).apply()
        }

    var autoSyncOnStart: Boolean
        get() = prefs.getBoolean("auto_sync", true)
        set(value) {
            prefs.edit().putBoolean("auto_sync", value).apply()
        }

    var isSidebarMinimized: Boolean
        get() = prefs.getBoolean("sidebar_minimized", false)
        set(value) {
            prefs.edit().putBoolean("sidebar_minimized", value).apply()
        }

    var isGridView: Boolean
        get() = prefs.getBoolean("library_grid_view", true)
        set(value) {
            prefs.edit().putBoolean("library_grid_view", value).apply()
        }

    fun getDocumentInvertPreference(docId: String): Boolean? {
        if (docId.isBlank()) return null
        val key = "doc_invert_$docId"
        return if (prefs.contains(key)) prefs.getBoolean(key, false) else null
    }

    fun setDocumentInvertPreference(docId: String, invert: Boolean) {
        if (docId.isBlank()) return
        prefs.edit().putBoolean("doc_invert_$docId", invert).apply()
    }
}
