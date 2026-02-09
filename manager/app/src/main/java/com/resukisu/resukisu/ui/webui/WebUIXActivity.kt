package com.resukisu.resukisu.ui.webui

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.widget.Toast
import androidx.activity.compose.setContent
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.LoadingIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.viewmodel.compose.viewModel
import com.dergoogler.mmrl.platform.model.ModId
import com.dergoogler.mmrl.webui.activity.WXActivity
import com.dergoogler.mmrl.webui.client.WXClient
import com.dergoogler.mmrl.webui.util.WebUIOptions
import com.dergoogler.mmrl.webui.view.WebUIXView
import com.resukisu.resukisu.BuildConfig
import com.resukisu.resukisu.R
import com.resukisu.resukisu.ksuApp
import com.resukisu.resukisu.ui.theme.KernelSUTheme
import com.resukisu.resukisu.ui.viewmodel.ModuleViewModel
import com.resukisu.resukisu.ui.viewmodel.SuperUserViewModel
import kotlinx.coroutines.CoroutineScope
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream

class WebUIXActivity : WXActivity() {

    @OptIn(ExperimentalMaterial3ExpressiveApi::class)
    override suspend fun onRender(scope: CoroutineScope) {
        val moduleId = intent.getStringExtra("id")!!

        setContent {
            KernelSUTheme {
                val moduleViewModel = viewModel<ModuleViewModel>(
                    viewModelStoreOwner = ksuApp
                )

                LaunchedEffect(Unit) {
                    val moduleInfo = moduleViewModel.moduleList.find { info -> info.id == moduleId }

                    if (moduleInfo == null) {
                        Toast.makeText(
                            this@WebUIXActivity,
                            getString(R.string.no_such_module, moduleId),
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                        return@LaunchedEffect
                    }

                    if (!moduleInfo.hasWebUi || !moduleInfo.enabled || moduleInfo.remove) {
                        Toast.makeText(
                            this@WebUIXActivity,
                            getString(R.string.module_unavailable, moduleInfo.name),
                            Toast.LENGTH_LONG
                        ).show()
                        finish()
                    }
                }

                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    LoadingIndicator()
                }
            }
        }

        initPlatform(this)
        super.onRender(scope)

        init()
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun init() {
        val moduleId = intent.getStringExtra("id")!!

        val prefs = getSharedPreferences("settings", MODE_PRIVATE)

        setContent {
            KernelSUTheme {
                val webDebugging = prefs.getBoolean("enable_web_debugging", false)
                val erudaInject = prefs.getBoolean("use_webuix_eruda", false)
                val dark = isSystemInDarkTheme()
                val colorScheme = MaterialTheme.colorScheme

                LaunchedEffect(Unit) {
                    if (SuperUserViewModel.apps.isEmpty()) {
                        SuperUserViewModel().fetchAppList()
                    }
                }

                AndroidView(
                    factory = { context ->
                        val options = WebUIOptions(
                            context = context,
                            modId = ModId(moduleId),
                            debug = webDebugging,
                            appVersionCode = BuildConfig.VERSION_CODE,
                            isDarkMode = dark,
                            enableEruda = erudaInject,
                            cls = WebUIXActivity::class.java,
                            userAgentString = ksuApp.UserAgent,
                            colorScheme = colorScheme,
                            disableGlobalExitConfirm = true,
                            client = { options, insets, assetHandlers ->
                                object : WXClient(options, insets, assetHandlers) {
                                    override fun shouldInterceptRequest(
                                        view: WebView?,
                                        request: WebResourceRequest
                                    ): WebResourceResponse? {
                                        val url = request.url
                                        if (url.scheme.equals(
                                                "ksu",
                                                ignoreCase = true
                                            ) && url.host.equals("icon", ignoreCase = true)
                                        ) {
                                            val packageName = url.path?.substring(1)
                                            if (!packageName.isNullOrEmpty()) {
                                                val icon = AppIconUtil.loadAppIconSync(
                                                    context,
                                                    packageName,
                                                    512
                                                )
                                                if (icon != null) {
                                                    val stream = ByteArrayOutputStream()
                                                    icon.compress(
                                                        Bitmap.CompressFormat.PNG,
                                                        100,
                                                        stream
                                                    )
                                                    return WebResourceResponse(
                                                        "image/png", null,
                                                        ByteArrayInputStream(stream.toByteArray())
                                                    )
                                                }
                                            }
                                        }
                                        return super.shouldInterceptRequest(view, request)
                                    }
                                }
                            },
                        )

                        WebUIXView(options).apply {
                            wx.addJavascriptInterface<WebUIXWebviewInterfaceWrapper>(
                                initargs = arrayOf(
                                    WebUIXWebViewInterfaceImpl(
                                        wx, moduleId, this@WebUIXActivity
                                    )
                                ),
                                parameterTypes = arrayOf(
                                    WebUIXWebViewInterfaceImpl::class.java
                                )
                            )
                            view = this
                        }
                    }
                )

                config {
                    if (title != null) {
                        setActivityTitle("ReSukiSU - $title")
                    }
                }
            }
        }
    }
}