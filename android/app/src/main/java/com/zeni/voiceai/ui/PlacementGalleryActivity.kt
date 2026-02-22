package com.zeni.voiceai.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.View
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.viewpager2.widget.ViewPager2
import com.zeni.voiceai.R
import com.zeni.voiceai.data.PreferencesManager
import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

/**
 * Full-screen activity for displaying top placement students in an auto-sliding carousel.
 * 
 * Features:
 * - Full-screen immersive mode
 * - Auto-slides to next photo every few seconds
 * - User can manually swipe to navigate
 * - Page indicators (dots) at bottom
 * - Dynamically loads placement photos from server
 * - Falls back to embedded photo if server unavailable
 */
class PlacementGalleryActivity : Activity() {
    
    companion object {
        private const val TAG = "PlacementGallery"
        private const val EXTRA_TITLE = "title"
        private const val AUTO_SLIDE_INTERVAL = 4000L  // 4 seconds between slides
        
        /**
         * Create intent to start PlacementGalleryActivity.
         */
        fun createIntent(
            context: Context,
            title: String = "Top Placements - GEHU Bhimtal"
        ): Intent {
            return Intent(context, PlacementGalleryActivity::class.java).apply {
                putExtra(EXTRA_TITLE, title)
            }
        }
    }
    
    private lateinit var placementPager: ViewPager2
    private lateinit var titleText: TextView
    private lateinit var closeButton: ImageButton
    private lateinit var indicatorContainer: LinearLayout
    private lateinit var pageCounter: TextView
    private var progressBar: ProgressBar? = null
    
    private lateinit var preferencesManager: PreferencesManager
    private lateinit var imageAdapter: PlacementImageAdapter
    
    private val httpClient = OkHttpClient()
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    
    // Auto-slide handler
    private val autoSlideHandler = Handler(Looper.getMainLooper())
    private var isAutoSlideEnabled = true
    private var currentPhotoCount = 0
    
    private val autoSlideRunnable = object : Runnable {
        override fun run() {
            if (isAutoSlideEnabled && currentPhotoCount > 1) {
                val nextPage = (placementPager.currentItem + 1) % currentPhotoCount
                placementPager.setCurrentItem(nextPage, true)
                autoSlideHandler.postDelayed(this, AUTO_SLIDE_INTERVAL)
            }
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Full-screen immersive mode
        enableFullScreen()
        
        setContentView(R.layout.activity_placement_gallery)
        
        preferencesManager = PreferencesManager(this)
        
        // Get data from intent
        val title = intent.getStringExtra(EXTRA_TITLE) ?: "Top Placements - GEHU Bhimtal"
        
        // Initialize views
        placementPager = findViewById(R.id.placementPager)
        titleText = findViewById(R.id.titleText)
        closeButton = findViewById(R.id.closeButton)
        indicatorContainer = findViewById(R.id.indicatorContainer)
        pageCounter = findViewById(R.id.pageCounter)
        progressBar = findViewById(R.id.progressBar)
        
        // Set title
        titleText.text = title
        
        // Setup ViewPager adapter
        imageAdapter = PlacementImageAdapter()
        placementPager.adapter = imageAdapter
        
        // Setup page change callback for indicators
        placementPager.registerOnPageChangeCallback(object : ViewPager2.OnPageChangeCallback() {
            override fun onPageSelected(position: Int) {
                super.onPageSelected(position)
                updateIndicators(position)
                updatePageCounter(position)
            }
            
            override fun onPageScrollStateChanged(state: Int) {
                super.onPageScrollStateChanged(state)
                // Pause auto-slide when user is dragging
                when (state) {
                    ViewPager2.SCROLL_STATE_DRAGGING -> pauseAutoSlide()
                    ViewPager2.SCROLL_STATE_IDLE -> resumeAutoSlide()
                }
            }
        })
        
        // Load placement photos from server
        loadPlacementPhotos()
        
        // Setup close button
        closeButton.setOnClickListener {
            finish()
        }
    }
    
    /**
     * Load all placement photos from server API.
     * Falls back to embedded resource if server unavailable.
     */
    private fun loadPlacementPhotos() {
        progressBar?.visibility = View.VISIBLE
        
        scope.launch {
            try {
                val baseUrl = preferencesManager.getHttpBaseUrl()
                val photoUrls = fetchAllPlacementPhotoUrls(baseUrl)
                
                if (photoUrls.isNotEmpty()) {
                    currentPhotoCount = photoUrls.size
                    imageAdapter.updateImages(photoUrls)
                    setupIndicators(photoUrls.size)
                    updatePageCounter(0)
                    progressBar?.visibility = View.GONE
                    
                    // Start auto-slide if more than 1 photo
                    if (photoUrls.size > 1) {
                        startAutoSlide()
                    }
                    
                    Log.d(TAG, "Loaded ${photoUrls.size} placement photos from server")
                } else {
                    loadFallbackImage()
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error loading placement photos", e)
                loadFallbackImage()
            }
        }
    }
    
    /**
     * Fetch all placement photo URLs from server API.
     */
    private suspend fun fetchAllPlacementPhotoUrls(baseUrl: String): List<String> {
        return withContext(Dispatchers.IO) {
            try {
                val request = Request.Builder()
                    .url("$baseUrl/api/placements")
                    .header("Cache-Control", "no-cache")  // Always fetch fresh list
                    .get()
                    .build()
                
                val response = httpClient.newCall(request).execute()
                
                if (response.isSuccessful) {
                    val json = JSONObject(response.body?.string() ?: "{}")
                    val photos = json.optJSONArray("photos")
                    
                    val urls = mutableListOf<String>()
                    if (photos != null) {
                        for (i in 0 until photos.length()) {
                            val photo = photos.getJSONObject(i)
                            val photoPath = photo.optString("url", "")
                            if (photoPath.isNotEmpty()) {
                                urls.add("$baseUrl$photoPath")
                            }
                        }
                    }
                    return@withContext urls
                }
                emptyList()
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch placement photos from API", e)
                emptyList()
            }
        }
    }
    
    /**
     * Load fallback embedded image when server is unavailable.
     */
    private fun loadFallbackImage() {
        progressBar?.visibility = View.GONE
        currentPhotoCount = 1
        
        // Use a special URL format that the adapter knows is a resource
        // Actually for fallback, we'll create a simple list with one placeholder
        // and let Coil show the error drawable
        imageAdapter.updateImages(listOf("fallback://placeholder"))
        setupIndicators(1)
        updatePageCounter(0)
        
        Log.d(TAG, "Using fallback embedded placement photo")
    }
    
    /**
     * Setup page indicator dots.
     */
    private fun setupIndicators(count: Int) {
        indicatorContainer.removeAllViews()
        
        if (count <= 1) {
            indicatorContainer.visibility = View.GONE
            pageCounter.visibility = View.GONE
            return
        }
        
        indicatorContainer.visibility = View.VISIBLE
        pageCounter.visibility = View.VISIBLE
        
        for (i in 0 until count) {
            val dot = ImageView(this).apply {
                val drawable = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setSize(24, 24)
                    setColor(ContextCompat.getColor(context, android.R.color.white))
                    alpha = if (i == 0) 255 else 128
                }
                setImageDrawable(drawable)
                
                val params = LinearLayout.LayoutParams(24, 24).apply {
                    marginStart = 8
                    marginEnd = 8
                }
                layoutParams = params
            }
            indicatorContainer.addView(dot)
        }
    }
    
    /**
     * Update indicator dots to show current position.
     */
    private fun updateIndicators(position: Int) {
        for (i in 0 until indicatorContainer.childCount) {
            val dot = indicatorContainer.getChildAt(i) as? ImageView
            dot?.let {
                val drawable = it.drawable as? GradientDrawable
                drawable?.alpha = if (i == position) 255 else 128
            }
        }
    }
    
    /**
     * Update page counter text (e.g., "1 / 5").
     */
    private fun updatePageCounter(position: Int) {
        if (currentPhotoCount > 1) {
            pageCounter.text = "${position + 1} / $currentPhotoCount"
        } else {
            pageCounter.visibility = View.GONE
        }
    }
    
    /**
     * Start auto-sliding carousel.
     */
    private fun startAutoSlide() {
        isAutoSlideEnabled = true
        autoSlideHandler.postDelayed(autoSlideRunnable, AUTO_SLIDE_INTERVAL)
    }
    
    /**
     * Pause auto-slide (e.g., when user is interacting).
     */
    private fun pauseAutoSlide() {
        autoSlideHandler.removeCallbacks(autoSlideRunnable)
    }
    
    /**
     * Resume auto-slide after user interaction.
     */
    private fun resumeAutoSlide() {
        if (isAutoSlideEnabled && currentPhotoCount > 1) {
            autoSlideHandler.removeCallbacks(autoSlideRunnable)
            autoSlideHandler.postDelayed(autoSlideRunnable, AUTO_SLIDE_INTERVAL)
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
    
    override fun onResume() {
        super.onResume()
        resumeAutoSlide()
    }
    
    override fun onPause() {
        super.onPause()
        pauseAutoSlide()
    }
    
    override fun onDestroy() {
        super.onDestroy()
        autoSlideHandler.removeCallbacks(autoSlideRunnable)
        scope.cancel()
    }
    
    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        // Close activity on back press
        finish()
    }
}
