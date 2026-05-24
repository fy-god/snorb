package com.macropilot.app.wechat

import com.macropilot.app.automation.ActionOutcome
import com.macropilot.app.automation.DeviceActions

class WechatLauncher(
    private val actions: DeviceActions
) {
    fun openWechat(): ActionOutcome {
        return actions.openApp(WECHAT_PACKAGE)
    }

    companion object {
        const val WECHAT_PACKAGE = "com.tencent.mm"
    }
}

