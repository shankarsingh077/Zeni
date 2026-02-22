package com.zeni.voiceai.ui

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.View
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
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
 * Full-screen activity for displaying GEHU fee structure web pages.
 * 
 * Features:
 * - Full-screen immersive mode
 * - WebView with JavaScript enabled
 * - Read-only mode (no link navigation)
 * - Close button overlay at top-right corner
 * - Loading indicator
 */
class FeeStructureActivity : Activity() {
    
    companion object {
        private const val EXTRA_PROGRAM_ID = "program_id"
        private const val EXTRA_PROGRAM_NAME = "program_name"
        private const val EXTRA_FEE_URL = "fee_url"
        
        /**
         * Fee URL mappings for Bhimtal campus programs.
         */
        private val FEE_URLS = mapOf(
            // B.Tech Programs
            "btech-cse" to "https://gehu.ac.in/fee/btl/btech-cse",
            "btech-civil" to "https://gehu.ac.in/fee/btl/btech-civil",
            "btech-ece" to "https://gehu.ac.in/fee/btl/btech-ece",
            "btech-me" to "https://gehu.ac.in/fee/btl/btech-me",
            
            // MBA
            "mba" to "https://gehu.ac.in/fee/btl/mba",
            
            // B.Sc Nursing
            "bsc-nursing" to "https://gehu.ac.in/fee/btl/bsc-nursing",
            
            // BBA Programs
            "bba" to "https://gehu.ac.in/fee/btl/bba",
            "bba-hons" to "https://gehu.ac.in/fee/btl/bba-hons",
            "bba-hons-ifaa" to "https://gehu.ac.in/fee/btl/bba-hons-ifaa",
            
            // B.Com Programs
            "bcom" to "https://gehu.ac.in/fee/btl/bcom",
            "bcom-hons" to "https://gehu.ac.in/fee/btl/bcom-hons",
            "bcom-ifaa" to "https://gehu.ac.in/fee/btl/bcom-ifaa",
            
            // M.Tech
            "mtech-me" to "https://gehu.ac.in/fee/btl/mtech-me",
            
            // Diploma Programs
            "diploma-cse" to "https://gehu.ac.in/fee/btl/diploma-cse",
            "diploma-me" to "https://gehu.ac.in/fee/btl/diploma-me",
            "diploma-civil" to "https://gehu.ac.in/fee/btl/diploma-civil",
            
            // B.Pharm
            "bpharm" to "https://gehu.ac.in/fee/btl/bpharm",
            
            // BCA Programs
            "bca" to "https://gehu.ac.in/fee/btl/bca",
            "bca-ai-ds" to "https://gehu.ac.in/fee/btl/bca-ai-ds",
            
            // B.Sc IT
            "bsc-it" to "https://gehu.ac.in/fee/btl/bsc-it",
            
            // MCA Programs
            "mca" to "https://gehu.ac.in/fee/btl/mca",
            "mca-ai-ds" to "https://gehu.ac.in/fee/btl/mca-ai-ds"
        )
        
        /**
         * Get fee URL for a program ID.
         */
        fun getFeeUrl(programId: String): String? {
            return FEE_URLS[programId.lowercase()]
        }
        
        /**
         * Get all available program IDs.
         */
        fun getAvailablePrograms(): Set<String> {
            return FEE_URLS.keys
        }
        
        /**
         * Create intent to start FeeStructureActivity.
         */
        fun createIntent(
            context: Context,
            programId: String,
            programName: String,
            feeUrl: String
        ): Intent {
            return Intent(context, FeeStructureActivity::class.java).apply {
                putExtra(EXTRA_PROGRAM_ID, programId)
                putExtra(EXTRA_PROGRAM_NAME, programName)
                putExtra(EXTRA_FEE_URL, feeUrl)
            }
        }
    }
    
    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var titleText: TextView
    private lateinit var closeButton: ImageButton
    
    private var feeUrl: String = ""
    private var programName: String = ""
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Full-screen immersive mode
        enableFullScreen()
        
        setContentView(R.layout.activity_fee_structure)
        
        // Get data from intent
        val programId = intent.getStringExtra(EXTRA_PROGRAM_ID) ?: ""
        programName = intent.getStringExtra(EXTRA_PROGRAM_NAME) ?: "Fee Structure"
        feeUrl = intent.getStringExtra(EXTRA_FEE_URL) ?: ""
        
        // Initialize views
        webView = findViewById(R.id.webView)
        progressBar = findViewById(R.id.progressBar)
        titleText = findViewById(R.id.titleText)
        closeButton = findViewById(R.id.closeButton)
        
        // Set title
        titleText.text = programName
        
        // Setup close button
        closeButton.setOnClickListener {
            finish()
        }
        
        // Setup WebView (read-only mode)
        setupWebView()
        
        // Load the fee URL
        if (feeUrl.isNotEmpty()) {
            webView.loadUrl(feeUrl)
        }
    }
    
    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        webView.apply {
            settings.apply {
                // Enable JavaScript for proper rendering
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
                
                // Load images
                loadsImagesAutomatically = true
            }
            
            // WebView client - BLOCK ALL NAVIGATION (read-only mode)
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                    // Block all link clicks - return true to prevent navigation
                    return true
                }
                
                @Deprecated("Deprecated in Java")
                override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                    // Block all link clicks - return true to prevent navigation
                    return true
                }
                
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    progressBar.visibility = View.GONE
                }
            }
            
            // Chrome client for progress
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
        }
    }
    
    private fun enableFullScreen() {
        // Make the app go edge-to-edge
        WindowCompat.setDecorFitsSystemWindows(window, false)
        
        // Hide system bars
        val windowInsetsController = WindowInsetsControllerCompat(window, window.decorView)
        windowInsetsController.hide(WindowInsetsCompat.Type.systemBars())
        windowInsetsController.systemBarsBehavior = 
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }
    
    override fun onBackPressed() {
        // Close activity on back press
        finish()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        webView.destroy()
    }
}
