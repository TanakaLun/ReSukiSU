package com.resukisu.resukisu.ui.webui

import android.content.Context
import android.content.ServiceConnection
import com.dergoogler.mmrl.platform.Platform
import com.dergoogler.mmrl.platform.Platform.Companion.createPlatformIntent
import com.dergoogler.mmrl.platform.PlatformManager
import com.dergoogler.mmrl.platform.model.IProvider
import com.resukisu.resukisu.Natives
import com.topjohnwu.superuser.ipc.RootService

class KsuLibSuProvider(
    private val context: Context,
) : IProvider {
    override val name = "KsuLibSu"

    override fun isAvailable() = true

    override suspend fun isAuthorized() = Natives.isManager

    private val serviceIntent
        get() = context.createPlatformIntent<SuService>(Platform.SukiSU)

    override fun bind(connection: ServiceConnection) {
        RootService.bind(serviceIntent, connection)
    }

    override fun unbind(connection: ServiceConnection) {
        RootService.stop(serviceIntent)
    }
}

// webui x
suspend fun initPlatform(
    context: Context
) {
    PlatformManager.init {
        PlatformManager.from(KsuLibSuProvider(context))
    }
}