package com.roubao.autopilot.ble

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattServer
import android.bluetooth.BluetoothGattServerCallback
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.AdvertiseCallback
import android.bluetooth.le.AdvertiseData
import android.bluetooth.le.AdvertiseSettings
import android.bluetooth.le.BluetoothLeAdvertiser
import android.content.Context
import android.os.ParcelUuid
import com.roubao.autopilot.agent.Action
import com.roubao.autopilot.controller.DeviceController
import java.util.UUID

/**
 * BLE 命令接收器 — ESP32 通过 BLE 发命令给 roubao
 *
 * ESP32 作为 GATT Client, roubao 作为 GATT Server.
 * 协议: 纯文本数字编码, 用 WriteWithoutResponse 发送, 延迟最低.
 *
 * 命令格式:
 *   "1,540,960"       = tap at (540, 960)
 *   "3,100,900,100,300" = swipe from (100,900) to (100,300)
 *   "4,3"             = scroll up 3
 *   "5,3"             = scroll down 3
 *   "10,你好"          = type "你好"
 *   "20"              = home
 *   "21"              = back
 *   "22"              = recent apps
 *   "30,微信"          = open app "微信"
 *   "99,帮我点外卖"     = 复杂指令, 触发 AI 决策
 */
class BleCommandReceiver(
    private val context: Context,
    private val onAction: (Action) -> Unit,        // 简单动作直接执行
    private val onAICommand: (String) -> Unit       // 复杂指令触发 AI
) {
    companion object {
        // 自定义 BLE Service/Characteristic UUID
        val SERVICE_UUID = UUID.fromString("0000FFE0-0000-1000-8000-00805F9B34FB")
        val CMD_CHAR_UUID = UUID.fromString("0000FFE1-0000-1000-8000-00805F9B34FB")

        private const val TAG = "[BleReceiver]"

        /**
         * 将 BLE 收到的 Action 直接执行到设备
         */
        fun executeAction(action: Action, controller: DeviceController) {
            when (action.type) {
                "click", "tap" -> {
                    controller.tap(action.x ?: 0, action.y ?: 0)
                }
                "long_press" -> {
                    controller.longPress(action.x ?: 0, action.y ?: 0)
                }
                "swipe" -> {
                    if (action.direction != null) {
                        val (w, h) = controller.getScreenSize()
                        when (action.direction) {
                            "up" -> controller.swipe(w / 2, h * 2 / 3, w / 2, h / 3, 300)
                            "down" -> controller.swipe(w / 2, h / 3, w / 2, h * 2 / 3, 300)
                            "left" -> controller.swipe(w * 2 / 3, h / 2, w / 3, h / 2, 300)
                            "right" -> controller.swipe(w / 3, h / 2, w * 2 / 3, h / 2, 300)
                        }
                    } else {
                        controller.swipe(
                            action.x ?: 0, action.y ?: 0,
                            action.x2 ?: 0, action.y2 ?: 0
                        )
                    }
                }
                "type" -> {
                    action.text?.let { controller.type(it) }
                }
                "system_button" -> {
                    when (action.button) {
                        "Home" -> controller.home()
                        "Back" -> controller.back()
                        "Enter" -> controller.enter()
                    }
                }
                "open_app" -> {
                    action.text?.let { controller.openApp(it) }
                }
                "double_tap" -> {
                    controller.doubleTap(action.x ?: 0, action.y ?: 0)
                }
            }
        }
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val bluetoothAdapter: BluetoothAdapter = bluetoothManager.adapter
    private var gattServer: BluetoothGattServer? = null
    private var isRunning = false

    private val gattServerCallback = object : BluetoothGattServerCallback() {
        override fun onConnectionStateChange(device: BluetoothDevice, status: Int, newState: Int) {
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    println("$TAG ESP32 已连接: ${device.address}")
                    isRunning = true
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    println("$TAG ESP32 已断开: ${device.address}")
                    isRunning = false
                }
            }
        }

        override fun onCharacteristicWriteRequest(
            device: BluetoothDevice,
            requestId: Int,
            characteristic: BluetoothGattCharacteristic,
            preparedWrite: Boolean,
            responseNeeded: Boolean,
            offset: Int,
            value: ByteArray
        ) {
            // 关键: 使用 WriteWithoutResponse (responseNeeded=false)
            // ESP32 不需要等确认, 延迟降到最低
            val command = String(value, Charsets.UTF_8).trim()
            println("$TAG 收到: \"$command\" 来自 ${device.address}")

            val action = parseCommand(command)
            if (action != null) {
                if (action.type == "ai_query") {
                    action.text?.let { onAICommand(it) }
                } else {
                    onAction(action)
                }
            }

            // 不需要 response (ESP32 用 WriteWithoutResponse 发的)
            if (responseNeeded) {
                gattServer?.sendResponse(device, requestId, BluetoothGatt.GATT_SUCCESS, 0, null)
            }
        }
    }

    /**
     * 解析 ESP32 发来的数字编码命令
     */
    private fun parseCommand(cmd: String): Action? {
        if (cmd.isBlank()) return null

        return try {
            val parts = cmd.split(",").map { it.trim() }
            val code = parts[0].toIntOrNull() ?: return null

            when (code) {
                1 -> {  // tap(x, y)
                    val x = parts.getOrNull(1)?.toIntOrNull() ?: return null
                    val y = parts.getOrNull(2)?.toIntOrNull() ?: return null
                    Action(type = "click", x = x, y = y)
                }
                2 -> {  // long_press(x, y, duration_ms)
                    val x = parts.getOrNull(1)?.toIntOrNull() ?: return null
                    val y = parts.getOrNull(2)?.toIntOrNull() ?: return null
                    Action(type = "long_press", x = x, y = y)
                }
                3 -> {  // swipe(x1, y1, x2, y2)
                    val x1 = parts.getOrNull(1)?.toIntOrNull() ?: return null
                    val y1 = parts.getOrNull(2)?.toIntOrNull() ?: return null
                    val x2 = parts.getOrNull(3)?.toIntOrNull() ?: return null
                    val y2 = parts.getOrNull(4)?.toIntOrNull() ?: return null
                    Action(type = "swipe", x = x1, y = y1, x2 = x2, y2 = y2)
                }
                4 -> {  // scroll_up(n)
                    val n = parts.getOrNull(1)?.toIntOrNull() ?: 1
                    Action(type = "swipe", direction = "up")
                }
                5 -> {  // scroll_down(n)
                    val n = parts.getOrNull(1)?.toIntOrNull() ?: 1
                    Action(type = "swipe", direction = "down")
                }
                10 -> {  // type(text)
                    val text = parts.drop(1).joinToString(",")
                    Action(type = "type", text = text)
                }
                20 -> Action(type = "system_button", button = "Home")
                21 -> Action(type = "system_button", button = "Back")
                22 -> Action(type = "system_button", button = "Enter")
                30 -> {  // open_app
                    val name = parts.getOrNull(1) ?: return null
                    Action(type = "open_app", text = name)
                }
                99 -> {  // AI 复杂指令
                    val query = parts.drop(1).joinToString(",")
                    Action(type = "ai_query", text = query)
                }
                else -> null
            }
        } catch (e: Exception) {
            println("$TAG 解析失败: ${e.message}")
            null
        }
    }

    /**
     * 启动 BLE GATT Server, 等待 ESP32 连接
     */
    fun start(): Boolean {
        if (!bluetoothAdapter.isEnabled) {
            println("$TAG 蓝牙未开启")
            return false
        }

        try {
            gattServer = bluetoothManager.openGattServer(context, gattServerCallback)

            // 创建 Service + Characteristic
            val service = BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            val characteristic = BluetoothGattCharacteristic(
                CMD_CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE,
                BluetoothGattCharacteristic.PERMISSION_WRITE
            )
            service.addCharacteristic(characteristic)
            gattServer?.addService(service)

            // 开始广播 (让 ESP32 能发现)
            startAdvertising()
            println("$TAG BLE Server 已启动, 等待 ESP32 连接...")
            return true
        } catch (e: Exception) {
            println("$TAG 启动失败: ${e.message}")
            return false
        }
    }

    private fun startAdvertising() {
        val advertiser: BluetoothLeAdvertiser = bluetoothAdapter.bluetoothLeAdvertiser
            ?: return

        val settings = AdvertiseSettings.Builder()
            .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
            .setConnectable(true)
            .setTimeout(0)  // 不限时
            .setTxPowerLevel(AdvertiseSettings.ADVERTISE_TX_POWER_HIGH)
            .build()

        val data = AdvertiseData.Builder()
            .setIncludeDeviceName(true)
            .addServiceUuid(ParcelUuid(SERVICE_UUID))
            .build()

        advertiser.startAdvertising(settings, data, object : AdvertiseCallback() {
            override fun onStartSuccess(settingsInEffect: AdvertiseSettings) {
                println("$TAG 广播已开始, 设备名: ${bluetoothAdapter.name}")
            }
            override fun onStartFailure(errorCode: Int) {
                println("$TAG 广播失败: $errorCode")
            }
        })
    }

    fun stop() {
        gattServer?.close()
        gattServer = null
        isRunning = false
        println("$TAG 已停止")
    }

    fun isConnected(): Boolean = isRunning
}
