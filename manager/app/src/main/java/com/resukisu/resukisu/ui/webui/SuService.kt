package com.resukisu.resukisu.ui.webui

import android.content.Intent
import android.os.IBinder
import com.dergoogler.mmrl.platform.Platform
import com.dergoogler.mmrl.platform.service.ServiceManager
import com.topjohnwu.superuser.ipc.RootService

class SuService : RootService() {
    override fun onBind(intent: Intent): IBinder {
        return ServiceManager(Platform.SukiSU)
    }
}