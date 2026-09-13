package com.ppailab.agnesstudio.network

import com.ppailab.agnesstudio.model.MediaAttachment

object VideoMediaPolicy {
    fun validateForSubmission(
        attachments: List<MediaAttachment>,
        temporaryUploadEnabled: Boolean,
    ) {
        val missing = attachments.firstOrNull { it.remoteUrl == null && it.localPath == null }
        require(missing == null) { "素材缺少本地文件或公开 URL：${missing?.displayName}" }

        val unavailable = attachments.firstOrNull {
            RelayUrlCachePolicy.reusableUrl(it) == null && it.localPath == null
        }
        require(unavailable == null) {
            "素材链接已过期，且没有本地原文件：${unavailable?.displayName}。请重新选择素材后提交。"
        }
        val localOnly = attachments.filter { RelayUrlCachePolicy.reusableUrl(it) == null && it.localPath != null }
        require(temporaryUploadEnabled || localOnly.isEmpty()) {
            "Agnes 视频接口要求素材为公开 HTTPS URL。当前未启用素材中转，请改用 URL，或先在设置中启用公开素材中转池。"
        }
    }
}
