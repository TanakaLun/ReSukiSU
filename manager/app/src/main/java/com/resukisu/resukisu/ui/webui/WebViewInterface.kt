package com.resukisu.resukisu.ui.webui

import android.app.Activity
import android.content.pm.ApplicationInfo
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Window
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.widget.Toast
import androidx.core.content.pm.PackageInfoCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.dergoogler.mmrl.webui.interfaces.WXInterface
import com.dergoogler.mmrl.webui.interfaces.WXOptions
import com.resukisu.resukisu.ui.util.controlKpmModule
import com.resukisu.resukisu.ui.util.createRootShell
import com.resukisu.resukisu.ui.util.listKpmModules
import com.resukisu.resukisu.ui.util.listModules
import com.resukisu.resukisu.ui.util.withNewRootShell
import com.resukisu.resukisu.ui.viewmodel.SuperUserViewModel
import com.topjohnwu.superuser.CallbackList
import com.topjohnwu.superuser.ShellUtils
import com.topjohnwu.superuser.internal.UiThreadHandler
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.concurrent.CompletableFuture

@Suppress("unused")
class WebUIXWebviewInterfaceWrapper(
    wxOptions: WXOptions,
    private val delegate: WebUIXWebViewInterfaceImpl
) : WXInterface(wxOptions), WebViewInterface by delegate {
    override var name = "ksu"
    override var tag = "WebUIXWebviewInterfaceWrapper"

    @JavascriptInterface
    fun mmrl(): Boolean {
        return true
    }

    @JavascriptInterface
    fun execBool(cmd: String): Boolean {
        return withNewRootShell { ShellUtils.fastCmdResult(this, cmd) }
    }
}

class WebUIXWebViewInterfaceImpl(
    webView: WebView?,
    modId: String,
    private val activity: Activity?
) : AbstractWebViewInterface(webView, "/data/adb/modules/$modId") {
    @JavascriptInterface
    override fun enableEdgeToEdge(enable: Boolean) {
        // not support
    }

    @JavascriptInterface
    override fun exit() {
        activity?.finish()
    }
}

class KSUWebUIWebViewInterfaceImpl(
    private val state: WebUIState,
) : AbstractWebViewInterface(state.webView, state.modDir) {
    @JavascriptInterface
    override fun enableEdgeToEdge(enable: Boolean) {
        state.isInsetsEnabled = enable
    }

    @JavascriptInterface
    override fun exit() {
        state.requestExit()
    }
}

abstract class AbstractWebViewInterface(
    private val webView: WebView?,
    private val modDir: String,
) : WebViewInterface {
    @JavascriptInterface
    override fun exec(cmd: String): String {
        return withNewRootShell(true) { ShellUtils.fastCmd(this, cmd) }
    }

    @JavascriptInterface
    override fun exec(cmd: String, callbackFunc: String) {
        exec(cmd, null, callbackFunc)
    }

    private fun processOptions(sb: StringBuilder, options: String?) {
        val opts = if (options == null) JSONObject() else {
            JSONObject(options)
        }

        val cwd = opts.optString("cwd")
        if (!TextUtils.isEmpty(cwd)) {
            sb.append("cd ${cwd};")
        }

        opts.optJSONObject("env")?.let { env ->
            env.keys().forEach { key ->
                sb.append("export ${key}=${env.getString(key)};")
            }
        }
    }

    @JavascriptInterface
    override fun exec(
        cmd: String,
        options: String?,
        callbackFunc: String
    ) {
        val finalCommand = StringBuilder()
        processOptions(finalCommand, options)
        finalCommand.append(cmd)

        val result = withNewRootShell(true) {
            newJob().add(finalCommand.toString()).to(ArrayList(), ArrayList()).exec()
        }
        val stdout = result.out.joinToString(separator = "\n")
        val stderr = result.err.joinToString(separator = "\n")

        val jsCode =
            "javascript: (function() { try { ${callbackFunc}(${result.code}, ${
                JSONObject.quote(
                    stdout
                )
            }, ${JSONObject.quote(stderr)}); } catch(e) { console.error(e); } })();"
        webView?.post {
            webView.loadUrl(jsCode)
        }
    }

    @JavascriptInterface
    override fun spawn(command: String, args: String, options: String?, callbackFunc: String) {
        val finalCommand = StringBuilder()

        processOptions(finalCommand, options)

        if (!TextUtils.isEmpty(args)) {
            finalCommand.append(command).append(" ")
            JSONArray(args).let { argsArray ->
                for (i in 0 until argsArray.length()) {
                    finalCommand.append(argsArray.getString(i))
                    finalCommand.append(" ")
                }
            }
        } else {
            finalCommand.append(command)
        }

        val shell = createRootShell(true)

        val emitData = fun(name: String, data: String) {
            val jsCode =
                "javascript: (function() { try { ${callbackFunc}.${name}.emit('data', ${
                    JSONObject.quote(
                        data
                    )
                }); } catch(e) { console.error('emitData', e); } })();"
            webView?.post {
                webView.loadUrl(jsCode)
            }
        }

        val stdout = object : CallbackList<String>(UiThreadHandler::runAndWait) {
            override fun onAddElement(s: String) {
                emitData("stdout", s)
            }
        }

        val stderr = object : CallbackList<String>(UiThreadHandler::runAndWait) {
            override fun onAddElement(s: String) {
                emitData("stderr", s)
            }
        }

        val future = shell.newJob().add(finalCommand.toString()).to(stdout, stderr).enqueue()
        val completableFuture = CompletableFuture.supplyAsync {
            future.get()
        }

        completableFuture.thenAccept { result ->
            val emitExitCode =
                "javascript: (function() { try { ${callbackFunc}.emit('exit', ${result.code}); } catch(e) { console.error(`emitExit error: \${e}`); } })();"
            webView?.post {
                webView.loadUrl(emitExitCode)
            }

            if (result.code != 0) {
                val emitErrCode =
                    "javascript: (function() { try { var err = new Error(); err.exitCode = ${result.code}; err.message = ${
                        JSONObject.quote(
                            result.err.joinToString(
                                "\n"
                            )
                        )
                    };${callbackFunc}.emit('error', err); } catch(e) { console.error('emitErr', e); } })();"
                webView?.post {
                    webView.loadUrl(emitErrCode)
                }
            }
        }.whenComplete { _, _ ->
            runCatching { shell.close() }
        }
    }

    @JavascriptInterface
    override fun toast(msg: String) {
        webView?.post {
            Toast.makeText(webView.context, msg, Toast.LENGTH_SHORT).show()
        }
    }

    @JavascriptInterface
    override fun fullScreen(enable: Boolean) {
        val context = webView?.context
        if (context is Activity) {
            Handler(Looper.getMainLooper()).post {
                if (enable) {
                    hideSystemUI(context.window)
                } else {
                    showSystemUI(context.window)
                }
            }
        }
        enableEdgeToEdge(enable)
    }

    @JavascriptInterface
    override fun enableInsets(enable: Boolean) {
        enableEdgeToEdge(enable)
    }

    @JavascriptInterface
    override fun moduleInfo(): String {
        val moduleInfos = JSONArray(listModules())
        val currentModuleInfo = JSONObject()
        currentModuleInfo.put("moduleDir", modDir)
        val moduleId = File(modDir).name
        for (i in 0 until moduleInfos.length()) {
            val currentInfo = moduleInfos.getJSONObject(i)

            if (currentInfo.getString("id") != moduleId) {
                continue
            }

            val keys = currentInfo.keys()
            for (key in keys) {
                currentModuleInfo.put(key, currentInfo.get(key))
            }
            break
        }
        return currentModuleInfo.toString()
    }

    @JavascriptInterface
    override fun listPackages(type: String): String {
        val packageNames = SuperUserViewModel.apps
            .filter { appInfo ->
                val flags = appInfo.packageInfo.applicationInfo?.flags ?: 0
                when (type.lowercase()) {
                    "system" -> (flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    "user" -> (flags and ApplicationInfo.FLAG_SYSTEM) == 0
                    else -> true
                }
            }
            .map { it.packageName }
            .sorted()

        val jsonArray = JSONArray()
        for (pkgName in packageNames) {
            jsonArray.put(pkgName)
        }
        return jsonArray.toString()
    }

    @JavascriptInterface
    override fun getPackagesInfo(packageNamesJson: String): String {
        val packageNames = JSONArray(packageNamesJson)
        val jsonArray = JSONArray()
        val appMap = SuperUserViewModel.apps.associateBy { it.packageName }
        for (i in 0 until packageNames.length()) {
            val pkgName = packageNames.getString(i)
            val appInfo = appMap[pkgName]
            if (appInfo != null) {
                val pkg = appInfo.packageInfo
                val app = pkg.applicationInfo
                val obj = JSONObject()
                obj.put("packageName", pkg.packageName)
                obj.put("versionName", pkg.versionName ?: "")
                obj.put("versionCode", PackageInfoCompat.getLongVersionCode(pkg))
                obj.put("appLabel", appInfo.label)
                obj.put("isSystem", if (app != null) ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0) else JSONObject.NULL)
                obj.put("uid", app?.uid ?: JSONObject.NULL)
                jsonArray.put(obj)
            } else {
                val obj = JSONObject()
                obj.put("packageName", pkgName)
                obj.put("error", "Package not found or inaccessible")
                jsonArray.put(obj)
            }
        }
        return jsonArray.toString()
    }

    @JavascriptInterface
    override fun listAllKpm(): String {
        return listKpmModules()
    }

    @JavascriptInterface
    override fun controlKpm(name: String, args: String): Int {
        return controlKpmModule(name, args)
    }
}

fun hideSystemUI(window: Window) =
    WindowInsetsControllerCompat(window, window.decorView).let { controller ->
        controller.hide(WindowInsetsCompat.Type.systemBars())
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

fun showSystemUI(window: Window) =
    WindowInsetsControllerCompat(window, window.decorView).show(WindowInsetsCompat.Type.systemBars())

interface WebViewInterface {
    @JavascriptInterface
    fun exec(cmd: String): String

    @JavascriptInterface
    fun exec(cmd: String, callbackFunc: String)

    @JavascriptInterface
    fun exec(cmd: String, options: String?, callbackFunc: String)

    @JavascriptInterface
    fun spawn(command: String, args: String, options: String?, callbackFunc: String)

    @JavascriptInterface
    fun toast(msg: String)

    @JavascriptInterface
    fun fullScreen(enable: Boolean)

    @JavascriptInterface
    fun enableInsets(enable: Boolean = true)

    @JavascriptInterface
    fun enableEdgeToEdge(enable: Boolean = true)

    @JavascriptInterface
    fun moduleInfo(): String

    @JavascriptInterface
    fun listPackages(type: String): String

    @JavascriptInterface
    fun getPackagesInfo(packageNamesJson: String): String

    @JavascriptInterface
    fun exit()

    @JavascriptInterface
    fun listAllKpm(): String

    @JavascriptInterface
    fun controlKpm(name: String, args: String): Int
}