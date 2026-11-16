package com.mukimov.salom

import org.junit.Assert.assertEquals
import org.junit.Test

class LocalChatCacheTest {
    @Test
    fun cachedMessageRoundTripKeepsAttachmentReplyAndReceipt() {
        val message = Message(
            text = "Photo and voice context",
            mine = false,
            time = "14:28",
            remoteId = 92,
            attachment = Attachment("attachment-id", "photo.png", "image/png", 1250, kind = "photo"),
            receipt = "read",
            replyToId = 77,
            replyAuthor = "Вы",
            replyPreview = "Original note",
            edited = true,
            locationId = "location-id"
        )

        val restored = CachedMessage.from("peer-id", message).toMessage()

        assertEquals(message, restored)
    }

    @Test
    fun cachedConversationRoundTripKeepsPublicProfileAndPreview() {
        val preview = ConversationPreview(
            SalomAccount("peer-id", "Алишер", "alisher", "", true, "https://example.invalid/avatar"),
            "До скорого"
        )

        assertEquals(preview, CachedConversation.from(3, preview).toPreview())
    }

    @Test
    fun queuedMessageRoundTripKeepsIdAttachmentAndReply() {
        val attachment = Attachment("attachment-id", "photo.png", "image/png", 1250, local = true, kind = "photo")
        val queued = CachedOutgoing.create("client-id", "peer-id", "Send when online", 1_700_000_000_000, attachment, 41, "Вы", "Previous text")

        assertEquals("client-id", queued.clientId)
        assertEquals("peer-id", queued.peerId)
        assertEquals(attachment, queued.attachment())
        val expectedTime = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(queued.createdAt))
        assertEquals(Message("Send when online", true, expectedTime, attachment = attachment, receipt = "queued", replyToId = 41, replyAuthor = "Вы", replyPreview = "Previous text"), queued.toMessage())
    }
}
