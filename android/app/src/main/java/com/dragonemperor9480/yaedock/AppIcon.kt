package com.dragonemperor9480.yaedock

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager

/**
 * The launcher icons, one per built-in theme. Each is an activity-alias in the
 * manifest; exactly one is enabled at a time. [alias] is its name there,
 * [background] and [foreground] draw its preview in Settings.
 */
enum class AppIcon(val label: String, val alias: String, val background: Int, val foreground: Int) {
    MIDNIGHT("Midnight", ".MainActivity", R.color.ic_launcher_background, R.drawable.ic_launcher_foreground),
    PAPER("Paper", ".IconPaper", R.color.ic_launcher_background_paper, R.drawable.ic_launcher_foreground_paper),
    VOID("Void", ".IconVoid", R.color.ic_launcher_background_void, R.drawable.ic_launcher_foreground_void),
    OCEAN("Ocean", ".IconOcean", R.color.ic_launcher_background_ocean, R.drawable.ic_launcher_foreground_ocean),
    SAKURA("Sakura", ".IconSakura", R.color.ic_launcher_background_sakura, R.drawable.ic_launcher_foreground_sakura);

    private fun component(context: Context) = ComponentName(context, context.packageName + alias)

    companion object {
        /** The icon the launcher shows now; Midnight is the one the manifest enables. */
        fun current(context: Context): AppIcon {
            val pm = context.packageManager
            return entries.firstOrNull { icon ->
                when (pm.getComponentEnabledSetting(icon.component(context))) {
                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
                    PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> icon == MIDNIGHT
                    else -> false
                }
            } ?: MIDNIGHT
        }

        /**
         * Shows [icon] in the launcher. The new entry is enabled before the old ones
         * are disabled, so the app always has one. The launcher may take a moment to
         * update, and some move the icon off the home screen when it changes.
         */
        fun set(context: Context, icon: AppIcon) {
            val pm = context.packageManager
            fun state(of: AppIcon, enabled: Boolean) = pm.setComponentEnabledSetting(
                of.component(context),
                if (enabled) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                PackageManager.DONT_KILL_APP,
            )
            state(icon, true)
            for (other in entries) if (other != icon) state(other, false)
        }
    }
}
