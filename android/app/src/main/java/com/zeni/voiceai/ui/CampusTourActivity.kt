package com.zeni.voiceai.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.view.WindowManager
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.ImageButton
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.zeni.voiceai.R

/**
 * Full-screen activity for displaying Matterport 360° campus tours.
 * 
 * Features:
 * - Full-screen immersive mode
 * - WebView with JavaScript enabled for Matterport
 * - Back button overlay to return to conversation
 * - Loading indicator
 */
class CampusTourActivity : Activity() {
    
    companion object {
        private const val EXTRA_TOUR_ID = "tour_id"
        private const val EXTRA_TOUR_NAME = "tour_name"
        private const val EXTRA_TOUR_URL = "tour_url"
        private const val EXTRA_TOUR_DESCRIPTION = "tour_description"
        
        /**
         * Create intent to start CampusTourActivity.
         */
        fun createIntent(
            context: Context,
            tourId: String,
            tourName: String,
            tourUrl: String,
            tourDescription: String
        ): Intent {
            return Intent(context, CampusTourActivity::class.java).apply {
                putExtra(EXTRA_TOUR_ID, tourId)
                putExtra(EXTRA_TOUR_NAME, tourName)
                putExtra(EXTRA_TOUR_URL, tourUrl)
                putExtra(EXTRA_TOUR_DESCRIPTION, tourDescription)
            }
        }
    }
    
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var titleText: TextView
    private lateinit var backButton: ImageButton
    
    private var tourUrl: String = ""
    private var tourName: String = ""
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Full-screen immersive mode
        enableFullScreen()
        
        setContentView(R.layout.activity_campus_tour)
        
        // Get tour data from intent
        val tourId = intent.getStringExtra(EXTRA_TOUR_ID) ?: ""
        tourName = intent.getStringExtra(EXTRA_TOUR_NAME) ?: "Campus Tour"
        tourUrl = intent.getStringExtra(EXTRA_TOUR_URL) ?: ""
        val tourDescription = intent.getStringExtra(EXTRA_TOUR_DESCRIPTION) ?: ""
        
        // Initialize views
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        titleText = findViewById(R.id.titleText)
        backButton = findViewById(R.id.backButton)
        
        // Set title
        titleText.text = tourName
        
        // Setup back button
        backButton.setOnClickListener {
            finish()
        }
        
        // Setup WebView
        setupWebView()
        
        // Load the tour URL
        if (tourUrl.isNotEmpty()) {
            webView.loadUrl(tourUrl)
        }
    }
    
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.apply {
            settings.apply {
                // Enable JavaScript (required for Matterport)
                javaScriptEnabled = true
                
                // Enable DOM storage
                domStorageEnabled = true
                
                // Enable hardware acceleration
                setLayerType(View.LAYER_TYPE_HARDWARE, null)
                
                // Allow mixed content (http/https)
                mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                
                // Enable zoom controls
                builtInZoomControls = true
                displayZoomControls = false
                
                // Cache settings
                cacheMode = WebSettings.LOAD_DEFAULT
                
                // Media playback
                mediaPlaybackRequiresUserGesture = false
                
                // User agent (desktop for better Matterport experience)
                userAgentString = userAgentString.replace("Mobile", "")
            }
            
            // WebView client for page navigation
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    progressBar.visibility = View.GONE
                }
            }
            
            // Chrome client for JavaScript dialogs, progress, etc.
            webChromeClient = object : WebChromeClient() {
                override fun onProgressChanged(view: WebView?, newProgress: Int) {
                    super.onProgressChanged(view, newProgress)
                    if (newProgress < 100) {
                        progressBar.visibility = View.VISIBLE
                        progressBar.progress = newProgress
                    } else {
                        progressBar.visibility = View.GONE
                    }
                }
            }
            
            // Enable debugging for development
            WebView.setWebContentsDebuggingEnabled(true)
        }
    }
    
    private fun enableFullScreen() {
        // Keep screen on
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        
        // Enable edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        // Hide system bars
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
    }
    
    override fun onResume() {
        super.onResume()
        webView.onResume()
    }
    
    override fun onPause() {
        super.onPause()
        webView.onPause()
    }
    
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) {
            webView.goBack()
        } else {
            super.onBackPressed()
        }
    }
    
    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}
