package com.xbw.tv.data.display

import android.content.Context

/** 显示偏好独立保存；每次进入游戏时读取，不影响按键映射。 */
object DisplaySettings {
    private const val PREF = "xbw_display"
    private const val AUTO_ROTATE = "auto_rotate"

    // 默认关闭，升级后保留原来的画面；侧着显示的竖版游戏可开启自动转正。
    fun isAutoRotate(context: Context): Boolean = context.applicationContext
        .getSharedPreferences(PREF, Context.MODE_PRIVATE).getBoolean(AUTO_ROTATE, false)

    fun setAutoRotate(context: Context, enabled: Boolean) {
        context.applicationContext.getSharedPreferences(PREF, Context.MODE_PRIVATE)
            .edit().putBoolean(AUTO_ROTATE, enabled).apply()
    }
}
