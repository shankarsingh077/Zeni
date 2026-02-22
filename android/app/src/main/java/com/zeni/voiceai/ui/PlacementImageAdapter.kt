package com.zeni.voiceai.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import androidx.recyclerview.widget.RecyclerView
import coil.load
import coil.request.CachePolicy
import com.zeni.voiceai.R

/**
 * Adapter for ViewPager2 to display placement photos in a carousel.
 * Supports both server URLs and drawable resource IDs.
 */
class PlacementImageAdapter(
    private var imageUrls: List<String> = emptyList()
) : RecyclerView.Adapter<PlacementImageAdapter.ImageViewHolder>() {

    private var fallbackResourceId: Int = R.drawable.placement_photo

    class ImageViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        val imageView: ImageView = itemView.findViewById(R.id.carouselImage)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ImageViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_placement_image, parent, false)
        return ImageViewHolder(view)
    }

    override fun onBindViewHolder(holder: ImageViewHolder, position: Int) {
        val url = imageUrls[position]
        
        holder.imageView.load(url) {
            crossfade(true)
            memoryCachePolicy(CachePolicy.DISABLED)  // Always fetch fresh from server
            diskCachePolicy(CachePolicy.DISABLED)    // Don't cache on disk
            error(fallbackResourceId)
            placeholder(android.R.color.darker_gray)
        }
    }

    override fun getItemCount(): Int = imageUrls.size

    /**
     * Update the list of image URLs and refresh the adapter.
     */
    fun updateImages(newUrls: List<String>) {
        imageUrls = newUrls
        notifyDataSetChanged()
    }

    /**
     * Set fallback resource to use when server image fails to load.
     */
    fun setFallbackResource(resourceId: Int) {
        fallbackResourceId = resourceId
    }
}
