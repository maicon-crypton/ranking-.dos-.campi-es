package com.aistudio.santrirank.mcdfbx

import android.app.Activity
import android.content.ClipData
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.view.View
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.aistudio.santrirank.mcdfbx.data.SantriRepository
import java.io.File

class MainActivity : ComponentActivity() {

    private lateinit var uploadLauncher: ActivityResultLauncher<Intent>
    private lateinit var webView: WebView
    private lateinit var androidAppBridge: AndroidAppBridge
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var cameraPhotoUri: Uri? = null
    private var cameraPhotoFile: File? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.statusBarColor = android.graphics.Color.rgb(247, 248, 250)
        window.navigationBarColor = android.graphics.Color.rgb(247, 248, 250)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }

        uploadLauncher = registerForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) { result ->
            val callback = filePathCallback ?: return@registerForActivityResult
            filePathCallback = null
            val uri = if (result.resultCode == Activity.RESULT_OK) {
                result.data?.data ?: cameraPhotoUri?.takeIf {
                    cameraPhotoFile?.let { it.isFile && it.length() > 0L } == true
                }
            } else {
                null
            }
            if (uri == null) cameraPhotoFile?.delete()
            callback.onReceiveValue(uri?.let { arrayOf(it) })
            cameraPhotoUri = null
            cameraPhotoFile = null
        }

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            settings.allowFileAccessFromFileURLs = false
            settings.allowUniversalAccessFromFileURLs = false
            settings.setSupportZoom(false)
            overScrollMode = View.OVER_SCROLL_NEVER
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView,
                    request: WebResourceRequest
                ): Boolean {
                    if (!request.isForMainFrame) return false
                    return !request.url.toString().startsWith("file:///android_asset/")
                }
            }
            webChromeClient = object : WebChromeClient() {
                override fun onShowFileChooser(
                    webView: WebView,
                    callback: ValueCallback<Array<Uri>>,
                    fileChooserParams: FileChooserParams
                ): Boolean {
                    filePathCallback?.onReceiveValue(null)
                    filePathCallback = callback
                    return try {
                        val captureIntent = if (fileChooserParams.isCaptureEnabled) {
                            createCameraIntent()
                        } else {
                            null
                        }
                        if (captureIntent != null) {
                            uploadLauncher.launch(captureIntent)
                        } else {
                            val galleryIntent = Intent(Intent.ACTION_GET_CONTENT).apply {
                                addCategory(Intent.CATEGORY_OPENABLE)
                                type = "image/*"
                            }
                            uploadLauncher.launch(Intent.createChooser(galleryIntent, "Escolha uma foto"))
                        }
                        true
                    } catch (e: Exception) {
                        filePathCallback = null
                        callback.onReceiveValue(null)
                        android.util.Log.e("SantriRank", "Não foi possível abrir a câmera ou galeria.", e)
                        false
                    }
                }
            }
        }
        androidAppBridge = AndroidAppBridge(this, SantriRepository.getInstance(this), webView)
        webView.addJavascriptInterface(androidAppBridge, "AndroidApp")

        val rootView = FrameLayout(this).apply {
            addView(
                webView,
                FrameLayout.LayoutParams(
                    FrameLayout.LayoutParams.MATCH_PARENT,
                    FrameLayout.LayoutParams.MATCH_PARENT
                )
            )
        }
        ViewCompat.setOnApplyWindowInsetsListener(rootView) { _, insets ->
            val safeArea = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            (webView.layoutParams as FrameLayout.LayoutParams).apply {
                setMargins(safeArea.left, safeArea.top, safeArea.right, safeArea.bottom)
            }.also { webView.layoutParams = it }
            insets
        }
        setContentView(rootView)
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript(
                    "(function(){return window.handleAndroidBack ? window.handleAndroidBack() : false;})()"
                ) { handled ->
                    if (handled != "true") {
                        isEnabled = false
                        onBackPressedDispatcher.onBackPressed()
                        isEnabled = true
                    }
                }
            }
        })
        webView.loadUrl("file:///android_asset/index.html?app=1")
    }

    private fun createCameraIntent(): Intent? {
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (intent.resolveActivity(packageManager) == null) return null

        val imageFile = File.createTempFile("santrirank_", ".jpg", cacheDir)
        cameraPhotoFile = imageFile
        cameraPhotoUri = FileProvider.getUriForFile(
            this,
            "${applicationContext.packageName}.fileprovider",
            imageFile
        )
        return intent.apply {
            putExtra(MediaStore.EXTRA_OUTPUT, cameraPhotoUri)
            clipData = ClipData.newRawUri("Foto capturada", cameraPhotoUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }
    }

    override fun onDestroy() {
        if (::androidAppBridge.isInitialized) {
            androidAppBridge.dispose()
        }
        webView.destroy()
        super.onDestroy()
    }
}
