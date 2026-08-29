package com.linger.app.service

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ScreenContextTest {

    @Test fun nullViewId_returnsNull() = assertNull(inferContextFromViewId(null))

    @Test fun unknownViewId_returnsNull() = assertNull(inferContextFromViewId("com.instagram.android:id/some_mystery_view"))

    @Test fun clipsViewer_returnsReels() = assertEquals("reels", inferContextFromViewId("com.instagram.android:id/clips_viewer_view_pager"))

    @Test fun likeButton_returnsReels() = assertEquals("reels", inferContextFromViewId("com.instagram.android:id/like_button"))

    @Test fun saveButton_returnsReels() = assertEquals("reels", inferContextFromViewId("com.instagram.android:id/save_button"))

    @Test fun rowFeedLike_returnsFeed() = assertEquals("feed", inferContextFromViewId("com.instagram.android:id/row_feed_button_like"))

    @Test fun rowFeedSave_returnsFeed() = assertEquals("feed", inferContextFromViewId("com.instagram.android:id/row_feed_button_save"))

    @Test fun commentView_returnsComments() = assertEquals("comments", inferContextFromViewId("com.instagram.android:id/comment_text"))

    @Test fun stickyHeader_returnsComments() = assertEquals("comments", inferContextFromViewId("com.instagram.android:id/sticky_header_list"))

    @Test fun directTab_returnsDm() = assertEquals("dm", inferContextFromViewId("com.instagram.android:id/direct_tab"))

    @Test fun inboxRecycler_returnsDm() = assertEquals("dm", inferContextFromViewId("com.instagram.android:id/inbox_refreshable_thread_list_recyclerview"))

    @Test fun directShare_returnsShare() = assertEquals("share", inferContextFromViewId("com.instagram.android:id/direct_private_share_recipients_recycler_view"))

    @Test fun shareSheet_returnsShare() = assertEquals("share", inferContextFromViewId("com.instagram.android:id/direct_share_sheet_grid_view_pog"))

    @Test fun storyView_returnsStory() = assertEquals("story", inferContextFromViewId("com.instagram.android:id/story_viewer_container"))

    @Test fun profileView_returnsProfile() = assertEquals("profile", inferContextFromViewId("com.instagram.android:id/profile_header"))
}
