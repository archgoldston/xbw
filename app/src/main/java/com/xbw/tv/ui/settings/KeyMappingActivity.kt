package com.xbw.tv.ui.settings

import android.os.Bundle
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.xbw.tv.R
import com.xbw.tv.input.GameButton
import com.xbw.tv.input.KeySettings
import com.xbw.tv.input.MotionKeyBridge
import com.xbw.tv.input.PlayerInputRouter
import com.xbw.tv.ui.common.Nav

/** 原生 libretro 1~4 人按键设置。每位玩家的物理映射彼此独立。 */
class KeyMappingActivity : AppCompatActivity() {
    private lateinit var container: LinearLayout
    private lateinit var router: PlayerInputRouter
    private lateinit var deviceRow: TextView
    private var player = 0 // libretro 端口 0..3
    private val playerTabs = mutableListOf<TextView>()
    private val rows = mutableMapOf<GameButton, RowHolder>()
    private var capturingFor: GameButton? = null
    private var captureArmedAt = 0L
    private val hatX = intArrayOf(0)
    private val hatY = intArrayOf(0)
    private val stickX = intArrayOf(0)
    private val stickY = intArrayOf(0)

    private inner class RowHolder(val root: View, val value: TextView)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        router = PlayerInputRouter(this)
        val scroll = ScrollView(this).apply {
            setBackgroundResource(R.drawable.bg_lobby)
            isFillViewport = true
        }
        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(40), dp(24), dp(40), dp(30))
        }
        scroll.addView(container)
        setContentView(scroll)

        container.addView(textLine("四人按键映射", 26f, true))
        container.addView(textLine(
            "先选择玩家，再为该玩家指定手柄并逐键设置。点按按键行后，在对应手柄上按一下即可绑定。" +
                " 游戏中的 Select 可用于投币；菜单用遥控器 MENU / BACK 或手柄 MODE。",
            14f, false
        ).apply { setPadding(0, dp(6), 0, dp(16)) })

        val tabs = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (p in 0..3) {
            val tab = TextView(this).apply {
                text = "玩家${p + 1}"
                textSize = 17f
                gravity = Gravity.CENTER
                setTextColor(ContextCompat.getColor(context, R.color.xbw_text_primary))
                setPadding(dp(12), dp(17), dp(12), dp(17))
                isFocusable = true
                layoutParams = LinearLayout.LayoutParams(0, dp(64), 1f).apply {
                    marginEnd = dp(6)
                }
                setOnClickListener { switchPlayer(p) }
                setOnKeyListener { _, code, event ->
                    if (event.action == KeyEvent.ACTION_DOWN && code == KeyEvent.KEYCODE_BUTTON_A) {
                        switchPlayer(p); true
                    } else false
                }
            }
            tabs.addView(tab)
            playerTabs += tab
        }
        container.addView(tabs)

        deviceRow = textLine("", 15f, false).apply {
            setBackgroundResource(R.drawable.bg_setting_item)
            setPadding(dp(14), dp(18), dp(14), dp(18))
            isFocusable = true
            setOnClickListener { chooseDevice() }
        }
        container.addView(deviceRow)
        container.addView(textLine("以下设置独立保存，不会覆盖其他玩家：", 13f, false)
            .apply { setPadding(0, dp(15), 0, dp(10)) })
        for (btn in GameButton.entries) addRow(btn)
        val reset = textLine("恢复当前玩家的默认按键", 16f, true).apply {
            setBackgroundResource(R.drawable.bg_setting_item)
            setPadding(dp(14), dp(20), dp(14), dp(20))
            isFocusable = true
            setOnClickListener {
                AlertDialog.Builder(this@KeyMappingActivity)
                    .setTitle("恢复玩家${player + 1}默认按键？")
                    .setPositiveButton("恢复") { _, _ ->
                        KeySettings.resetPlayer(player)
                        refreshAll()
                        Nav.toast(this@KeyMappingActivity, "玩家${player + 1}已恢复默认键位")
                    }
                    .setNegativeButton("取消", null).show()
            }
        }
        container.addView(reset)
        refreshAll()
        tabs.post { playerTabs[0].requestFocus() }
    }

    private fun textLine(value: String, size: Float, bold: Boolean): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(ContextCompat.getColor(context,
            if (bold) R.color.xbw_text_primary else R.color.xbw_text_secondary))
        if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
    }

    private fun switchPlayer(p: Int) {
        if (capturingFor != null) stopCapture("已取消捕获")
        player = p
        refreshAll()
        playerTabs[p].requestFocus()
    }

    private fun chooseDevice() {
        val devices = router.devices()
        val items = arrayOf("自动分配（优先按手柄接入顺序）") + devices.map {
            "${it.name}（设备 #${it.id}${if (it.controller) " / 手柄" else " / 键盘遥控"}）"
        }
        AlertDialog.Builder(this)
            .setTitle("为玩家${player + 1}选择输入设备")
            .setItems(items) { _, index ->
                router.assign(player, if (index == 0) null else devices[index - 1].id)
                refreshAll()
                Nav.toast(this, "已保存玩家${player + 1}的设备分配")
            }
            .setNegativeButton("取消", null)
            .show()
    }

    private fun addRow(btn: GameButton) {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = dp(62)
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setBackgroundResource(R.drawable.bg_setting_item)
            isFocusable = true
            layoutParams = LinearLayout.LayoutParams(-1, -2).also { it.bottomMargin = dp(7) }
        }
        val label = textLine(btn.label, 16f, true).apply {
            layoutParams = LinearLayout.LayoutParams(0, -2, 1f)
        }
        val value = textLine("", 13f, false).apply {
            gravity = Gravity.END
            layoutParams = LinearLayout.LayoutParams(0, -2, 1.4f)
        }
        row.addView(label)
        row.addView(value)
        row.setOnClickListener { if (capturingFor == btn) stopCapture("已取消") else startCapture(btn) }
        row.setOnKeyListener { _, keyCode, event ->
            if (capturingFor != null) return@setOnKeyListener true
            if (event.action != KeyEvent.ACTION_DOWN) return@setOnKeyListener false
            when (keyCode) {
                KeyEvent.KEYCODE_DPAD_CENTER, KeyEvent.KEYCODE_ENTER,
                KeyEvent.KEYCODE_BUTTON_A, KeyEvent.KEYCODE_BUTTON_START -> {
                    startCapture(btn); true
                }
                else -> false
            }
        }
        container.addView(row)
        rows[btn] = RowHolder(row, value)
    }

    private fun startCapture(btn: GameButton) {
        capturingFor = btn
        captureArmedAt = SystemClock.uptimeMillis() + 280
        hatX[0] = 0; hatY[0] = 0; stickX[0] = 0; stickY[0] = 0
        refreshAll()
        Nav.toast(this, "玩家${player + 1}：请按下「${btn.label}」对应的手柄键（BACK 取消）")
    }

    private fun acceptPhysical(code: Int, deviceId: Int) {
        val target = capturingFor ?: return
        if (code == KeyEvent.KEYCODE_BACK) { stopCapture("已取消"); return }
        if (MotionKeyBridge.isIgnorableSystemKey(code) || SystemClock.uptimeMillis() < captureArmedAt) return
        KeySettings.setPhysical(player, code, target)
        // 用对应手柄直接录入键位时，同步将它指派给该玩家。
        val dev = InputDevice.getDevice(deviceId)
        val controller = dev != null && ((dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
            (dev.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK)
        if (controller || player > 0 && dev != null) router.assign(player, deviceId)
        stopCapture("玩家${player + 1}：${MotionKeyBridge.describeBind(code)} → ${target.label} 已保存")
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (capturingFor != null) {
            if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0)
                acceptPhysical(MotionKeyBridge.bindCode(event), event.deviceId)
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: MotionEvent): Boolean {
        if (capturingFor != null) {
            MotionKeyBridge.dispatchMotion(event, hatX, hatY, stickX, stickY) { code, down ->
                if (down) acceptPhysical(code, event.deviceId)
            }
            return true
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun stopCapture(message: String) {
        capturingFor = null
        refreshAll()
        Nav.toast(this, message)
    }

    private fun refreshAll() {
        for (p in 0..3) playerTabs[p].setBackgroundResource(
            if (p == player) R.drawable.btn_tv else R.drawable.bg_setting_item)
        deviceRow.text = "玩家${player + 1}使用的设备：${router.assignmentLabel(player)}\n点击选择 / 更换手柄"
        val mapping = KeySettings.snapshotPhysical(player)
        for ((btn, holder) in rows) {
            val keys = mapping.filterValues { it == btn }.keys.sorted()
            holder.value.text = if (capturingFor == btn) "正在等待按键…" else
                keys.take(4).joinToString(" / ") { MotionKeyBridge.describeBind(it) }
                    .ifBlank { "未绑定" } + if (keys.size > 4) " 等${keys.size}键" else ""
            holder.root.setBackgroundResource(
                if (capturingFor == btn) R.drawable.btn_tv else R.drawable.bg_setting_item)
        }
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
