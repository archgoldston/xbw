package com.xbw.tv.input

import android.content.Context
import android.view.InputDevice

/**
 * 为四个 libretro 端口分配 Android 输入设备。
 * 默认：真正的 USB/蓝牙手柄按系统设备 ID 的顺序占用 P1~P4；遥控器/键盘归 P1。
 * 用户在按键设置中可选定一个物理设备，覆盖该自动顺序（按 descriptor 持久化）。
 * 注意：一个 USB 接收器若只向 Android 暴露一个 InputDevice，就无法可靠区分内部的多个手柄。
 */
class PlayerInputRouter(context: Context) {
    private val pref = context.applicationContext.getSharedPreferences("multi_player_devices", Context.MODE_PRIVATE)

    data class Device(val id: Int, val name: String, val descriptor: String, val controller: Boolean)

    fun devices(): List<Device> = InputDevice.getDeviceIds().toList().mapNotNull { id ->
        val dev = InputDevice.getDevice(id) ?: return@mapNotNull null
        val controller = isController(dev)
        val keyboard = (dev.sources and InputDevice.SOURCE_KEYBOARD) == InputDevice.SOURCE_KEYBOARD
        val dpad = (dev.sources and InputDevice.SOURCE_DPAD) == InputDevice.SOURCE_DPAD
        if (!controller && !keyboard && !dpad) return@mapNotNull null
        Device(id, dev.name ?: "未知输入设备", dev.descriptor ?: "", controller)
    }.sortedBy { it.id }

    fun assignedDevice(player: Int): String? = if (player in 0..3)
        pref.getString("player_${player + 1}", null) else null

    fun assign(player: Int, deviceId: Int?) {
        if (player !in 0..3) return
        if (deviceId == null) {
            pref.edit().remove("player_${player + 1}").remove("player_id_${player + 1}").apply()
            return
        }
        val device = devices().firstOrNull { it.id == deviceId } ?: return
        // 同一物理设备只归一个玩家。将原来选它的玩家恢复自动分配。
        val editor = pref.edit()
        for (p in 0..3) {
            if (p != player && assignedDevice(p) == device.descriptor &&
                pref.getInt("player_id_${p + 1}", -1) == device.id) {
                editor.remove("player_${p + 1}").remove("player_id_${p + 1}")
            }
        }
        editor.putString("player_${player + 1}", device.descriptor)
            .putInt("player_id_${player + 1}", device.id).apply()
    }

    /** -1：第五个及更多手柄，忽略；0..3：对应 libretro 玩家。 */
    fun playerOf(deviceId: Int): Int {
        if (deviceId < 0) return 0 // 系统合成按键 / 遥控器兜底
        val list = devices()
        val target = list.firstOrNull { it.id == deviceId } ?: return 0
        val allocated = HashMap<Int, Int>()
        val used = HashSet<Int>()
        // 先锁定手工指定的设备；优先匹配 ID+descriptor，重启后改用 descriptor 恢复。
        for (p in 0..3) {
            val descriptor = assignedDevice(p) ?: continue
            val oldId = pref.getInt("player_id_${p + 1}", -1)
            val d = list.firstOrNull { it.id == oldId && it.descriptor == descriptor && it.id !in used }
                ?: list.firstOrNull { it.descriptor == descriptor && it.id !in used }
            if (d != null) {
                allocated[d.id] = p
                used.add(d.id)
            }
        }
        // 自动分配只针对实际 GAMEPAD/JOYSTICK，普通键盘/遥控器不抢 P2~P4。
        for (d in list) {
            if (!d.controller || d.id in used) continue
            val free = (0..3).firstOrNull { it !in allocated.values } ?: break
            allocated[d.id] = free
            used.add(d.id)
        }
        return allocated[deviceId] ?: if (target.controller) -1 else 0
    }

    fun assignmentLabel(player: Int): String {
        val selected = devices().filter { playerOf(it.id) == player &&
            (it.controller || assignedDevice(player) == it.descriptor) }
        return if (selected.isEmpty()) "自动分配 / 暂无手柄" else selected.joinToString("、") { "${it.name} (#${it.id})" }
    }

    companion object {
        private fun isController(dev: InputDevice): Boolean =
            (dev.sources and InputDevice.SOURCE_GAMEPAD) == InputDevice.SOURCE_GAMEPAD ||
                (dev.sources and InputDevice.SOURCE_JOYSTICK) == InputDevice.SOURCE_JOYSTICK
    }
}
