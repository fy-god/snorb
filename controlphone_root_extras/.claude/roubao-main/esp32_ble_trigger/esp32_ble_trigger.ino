/*
 * ESP32 BLE 触发器 — 肉包 (roubao) 的物理遥控器
 *
 * 功能:
 *   1. 扫描并连接 roubao 手机的 BLE GATT Server
 *   2. 通过串口 (USB) 接收命令，转发给手机
 *   3. 可选: GPIO 物理按键映射到预设命令
 *
 * 命令协议 (纯文本):
 *   "1,540,960"        = 点击 (540, 960)
 *   "2,540,960,1000"   = 长按 (540, 960) 1秒
 *   "3,100,900,100,300"= 滑动 (100,900)→(100,300)
 *   "4,3"              = 向上滚动 3 次
 *   "5,3"              = 向下滚动 3 次
 *   "10,你好"           = 输入文本 "你好"
 *   "20"               = Home 键
 *   "21"               = 返回键
 *   "22"               = 回车键
 *   "30,微信"           = 打开应用 "微信"
 *   "99,帮我点外卖"      = AI 复杂指令
 *
 * 硬件:
 *   - ESP32 开发板 (任何型号)
 *   - 可选: 物理按键 (GPIO 0 = 返回, GPIO 14 = 向下翻页, 等)
 */

#include <BLEDevice.h>
#include <BLEClient.h>
#include <BLEUtils.h>
#include <BLEScan.h>
#include <BLEAdvertisedDevice.h>

// roubao BLE Service/Characteristic UUID (必须与 BleCommandReceiver.kt 一致)
static BLEUUID SERVICE_UUID("0000ffe0-0000-1000-8000-00805f9b34fb");
static BLEUUID CMD_CHAR_UUID("0000ffe1-0000-1000-8000-00805f9b34fb");

// 连接参数
static const int SCAN_TIME_SEC = 5;      // 每次扫描 5 秒
static const int RECONNECT_DELAY_MS = 2000;

// GPIO 物理按键 (可选)
static const int BTN_BACK    = 0;   // GPIO 0  → 返回
static const int BTN_HOME    = 4;   // GPIO 4  → 回首页
static const int BTN_DOWN    = 14;  // GPIO 14 → 下翻一页
static const int BTN_UP      = 15;  // GPIO 15 → 上翻一页
static const int BTN_AI      = 5;   // GPIO 5  → 触发预设 AI 指令

// 预设 AI 指令 (通过 GPIO 触发)
static const char* PRESET_AI_COMMAND = "99,帮我点外卖";

// 去抖
static unsigned long lastBtnPress[5] = {0};
static const unsigned long DEBOUNCE_MS = 300;

BLEClient*  pClient  = nullptr;
BLERemoteCharacteristic* pCmdChar = nullptr;
bool connected = false;

// ---------------------------------------------------------------------------
// 扫描回调 — 找到 roubao 设备后立即停止扫描
// ---------------------------------------------------------------------------
class MyAdvertisedDeviceCallbacks: public BLEAdvertisedDeviceCallbacks {
    void onResult(BLEAdvertisedDevice advertisedDevice) {
        if (advertisedDevice.haveServiceUUID() &&
            advertisedDevice.isAdvertisingService(SERVICE_UUID)) {

            Serial.print("发现 roubao 设备: ");
            Serial.println(advertisedDevice.toString().c_str());

            // 停止扫描，准备连接
            BLEDevice::getScan()->stop();
        }
    }
};

// ---------------------------------------------------------------------------
// 连接 roubao 设备
// ---------------------------------------------------------------------------
bool connectToRoubao() {
    Serial.println("正在扫描 roubao 设备...");

    BLEScan* pScan = BLEDevice::getScan();
    pScan->setAdvertisedDeviceCallbacks(new MyAdvertisedDeviceCallbacks());
    pScan->setActiveScan(true);
    pScan->start(SCAN_TIME_SEC);

    delay(SCAN_TIME_SEC * 1000 + 500);  // 等待扫描完成
    pScan->clearResults();

    // 获取扫描结果，连接第一个匹配设备
    BLEAdvertisedDevice* targetDevice = nullptr;

    // 重新扫描一次以获取设备引用
    pScan->setAdvertisedDeviceCallbacks(new MyAdvertisedDeviceCallbacks());
    pScan->start(SCAN_TIME_SEC);

    // 等待回调触发 (在回调中 pScan->stop())
    unsigned long start = millis();
    while (millis() - start < SCAN_TIME_SEC * 1000) {
        delay(100);
    }
    pScan->clearResults();

    // 使用白名单方式重连：遍历扫描到的设备
    // 注意: ESP32 BLE Arduino 库的 scan 会自动聚合结果
    Serial.println("扫描完毕，尝试连接...");

    // 简化方式: 直接扫描连接
    pScan->setAdvertisedDeviceCallbacks(
        new class : public BLEAdvertisedDeviceCallbacks {
            void onResult(BLEAdvertisedDevice advertisedDevice) override {
                if (advertisedDevice.haveServiceUUID() &&
                    advertisedDevice.isAdvertisingService(SERVICE_UUID)) {

                    Serial.print("正在连接: ");
                    Serial.println(advertisedDevice.getAddress().toString().c_str());

                    pClient = BLEDevice::createClient();
                    pClient->connect(&advertisedDevice);

                    if (pClient->isConnected()) {
                        Serial.println("✅ 已连接!");

                        BLERemoteService* pService = pClient->getService(SERVICE_UUID);
                        if (pService != nullptr) {
                            pCmdChar = pService->getCharacteristic(CMD_CHAR_UUID);
                            if (pCmdChar != nullptr) {
                                Serial.println("✅ 已找到命令特征，就绪");
                                connected = true;
                            }
                        }
                    }
                    BLEDevice::getScan()->stop();
                }
            }
        }
    );
    pScan->start(SCAN_TIME_SEC);

    start = millis();
    while (millis() - start < SCAN_TIME_SEC * 1000 && !connected) {
        delay(100);
    }

    return connected;
}

// ---------------------------------------------------------------------------
// 发送命令到 roubao (WriteWithoutResponse, 最低延迟)
// ---------------------------------------------------------------------------
void sendCommand(const String& cmd) {
    if (!connected || pCmdChar == nullptr) {
        Serial.println("❌ 未连接");
        return;
    }

    Serial.print("📤 发送: \"");
    Serial.print(cmd);
    Serial.println("\"");

    pCmdChar->writeValue((uint8_t*)cmd.c_str(), cmd.length(), false);  // false = no response
}

// ---------------------------------------------------------------------------
// 处理串口输入
// ---------------------------------------------------------------------------
void handleSerial() {
    if (Serial.available()) {
        String input = Serial.readStringUntil('\n');
        input.trim();

        if (input.length() == 0) return;

        // 特殊命令
        if (input == "r" || input == "reconnect") {
            Serial.println("重新连接...");
            connected = false;
            if (pClient != nullptr) {
                pClient->disconnect();
            }
            connectToRoubao();
            return;
        }

        if (input == "s" || input == "status") {
            Serial.print("状态: ");
            Serial.println(connected ? "已连接" : "未连接");
            return;
        }

        // 帮助信息
        if (input == "h" || input == "help") {
            Serial.println("=== roubao BLE 触发器 ===");
            Serial.println("命令格式 (纯文本):");
            Serial.println("  1,x,y      点击");
            Serial.println("  2,x,y,ms   长按");
            Serial.println("  3,x1,y1,x2,y2  滑动");
            Serial.println("  4,n        向上滚动 n 次");
            Serial.println("  5,n        向下滚动 n 次");
            Serial.println("  10,text    输入文本");
            Serial.println("  20         Home");
            Serial.println("  21         返回");
            Serial.println("  22         回车");
            Serial.println("  30,name    打开应用");
            Serial.println("  99,query   AI 复杂指令");
            Serial.println("---");
            Serial.println("  s/status   查看连接状态");
            Serial.println("  r/reconnect  重新连接");
            Serial.println("  h/help     显示此帮助");
            return;
        }

        // 转发给手机
        if (!connected) {
            Serial.println("⚠ 未连接，正在重连...");
            connectToRoubao();
        }
        sendCommand(input);
    }
}

// ---------------------------------------------------------------------------
// 处理 GPIO 物理按键
// ---------------------------------------------------------------------------
void handleButtons() {
    unsigned long now = millis();

    // GPIO 0 - 返回
    checkButton(BTN_BACK, "21", lastBtnPress[0], now);

    // GPIO 4 - Home
    checkButton(BTN_HOME, "20", lastBtnPress[1], now);

    // GPIO 14 - 下翻一页
    checkButton(BTN_DOWN, "5,1", lastBtnPress[2], now);

    // GPIO 15 - 上翻一页
    checkButton(BTN_UP, "4,1", lastBtnPress[3], now);

    // GPIO 5 - 预设 AI 指令
    checkButton(BTN_AI, String(PRESET_AI_COMMAND), lastBtnPress[4], now);
}

void checkButton(int pin, const String& cmd, unsigned long& lastPress, unsigned long now) {
    if (digitalRead(pin) == LOW) {  // 低电平触发 (内部上拉)
        if (now - lastPress > DEBOUNCE_MS) {
            lastPress = now;
            Serial.print("🔘 GPIO ");
            Serial.print(pin);
            Serial.print(" → ");
            sendCommand(cmd);
        }
    }
}

// ---------------------------------------------------------------------------
// 初始化
// ---------------------------------------------------------------------------
void setup() {
    Serial.begin(115200);
    delay(1000);
    Serial.println("\n=== ESP32 roubao BLE 触发器 ===");
    Serial.println("输入 'h' 查看帮助");

    // 初始化 BLE
    BLEDevice::init("ESP32_Trigger");
    BLEDevice::setMTU(517);  // 最大 MTU, 支持长 AI 指令

    // 配置 GPIO 按键 (内部上拉, 按下 = LOW)
    pinMode(BTN_BACK, INPUT_PULLUP);
    pinMode(BTN_HOME, INPUT_PULLUP);
    pinMode(BTN_DOWN, INPUT_PULLUP);
    pinMode(BTN_UP,   INPUT_PULLUP);
    pinMode(BTN_AI,   INPUT_PULLUP);

    // 自动连接
    connectToRoubao();
}

// ---------------------------------------------------------------------------
// 主循环
// ---------------------------------------------------------------------------
void loop() {
    handleSerial();
    handleButtons();

    // 连接断开时自动重连
    if (!connected || (pClient != nullptr && !pClient->isConnected())) {
        static unsigned long lastReconnect = 0;
        unsigned long now = millis();
        if (now - lastReconnect > RECONNECT_DELAY_MS) {
            lastReconnect = now;
            connected = false;
            Serial.println("连接丢失，重连中...");
            connectToRoubao();
        }
    }

    delay(10);
}
