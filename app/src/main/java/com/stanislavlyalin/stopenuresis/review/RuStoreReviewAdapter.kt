package com.stanislavlyalin.stopenuresis.review

import android.content.Context
import ru.rustore.sdk.review.RuStoreReviewManagerFactory
import ru.rustore.sdk.review.errors.RuStoreReviewExists
import ru.rustore.sdk.review.model.ReviewInfo

internal class RuStoreReviewAdapter(context: Context) {
    private val manager by lazy { RuStoreReviewManagerFactory.create(context.applicationContext) }

    fun prepare(success: (ReviewInfo) -> Unit, failure: (Throwable) -> Unit) {
        manager.requestReviewFlow().addOnSuccessListener { success(it) }.addOnFailureListener { failure(it) }
    }

    fun launch(info: ReviewInfo, finished: (Throwable?) -> Unit) {
        manager.launchReviewFlow(info).addOnSuccessListener { finished(null) }.addOnFailureListener { finished(it) }
    }

    fun reviewExists(error: Throwable): Boolean = error is RuStoreReviewExists
}
