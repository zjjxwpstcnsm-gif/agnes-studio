package com.ppailab.agnesstudio.network

import com.ppailab.agnesstudio.model.AccessPlan
import com.ppailab.agnesstudio.model.AppSettings

object RatePolicy {
    const val TEXT = "text"
    const val VIDEO = "video_create"

    fun imageBucket(size: String) = "image_${size.uppercase()}"

    fun rpm(settings: AppSettings, bucket: String): Int {
        val rates = when (settings.accessPlan) {
            AccessPlan.FREE -> Rates(20, 20, 10, 1, 1, 1)
            AccessPlan.ENTERPRISE -> Rates(40, 40, 20, 1, 1, 2)
            AccessPlan.TOKEN_PLAN -> Rates(1_000, 100, 80, 1, 1, 5)
            AccessPlan.CUSTOM -> Rates(
                settings.customTextRpm,
                settings.customImage1kRpm,
                settings.customImage2kRpm,
                settings.customImage3kRpm,
                settings.customImage4kRpm,
                settings.customVideoRpm,
            )
        }
        return when (bucket) {
            TEXT -> rates.text
            "image_1K" -> rates.image1k
            "image_2K" -> rates.image2k
            "image_3K" -> rates.image3k
            "image_4K" -> rates.image4k
            VIDEO -> rates.video
            else -> rates.image1k
        }.coerceAtLeast(1)
    }

    private data class Rates(
        val text: Int,
        val image1k: Int,
        val image2k: Int,
        val image3k: Int,
        val image4k: Int,
        val video: Int,
    )
}
