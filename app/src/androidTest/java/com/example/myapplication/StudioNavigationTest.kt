package com.example.myapplication

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextReplacement
import org.junit.Rule
import org.junit.Test

class StudioNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun destinationsAndEmptyStatesAreReachableWithoutAPaidRequest() {
        compose.onNodeWithText("Mặc thử.\nThấy khác biệt.").assertIsDisplayed()
        compose.onNodeWithTag("nav_PHOTO").performClick()
        compose.onNodeWithText("Một diện mạo mới.").assertIsDisplayed()
        compose.onNodeWithTag("nav_LIVE").performClick()
        compose.onNodeWithText("Chuyển động cùng bạn.").assertIsDisplayed()
        compose.onNodeWithTag("nav_WARDROBE").performClick()
        compose.onNodeWithText("Tủ đồ đang chờ bạn").assertIsDisplayed()
        compose.onNodeWithText("Kết quả · 0").performClick()
        compose.onNodeWithText("Diện mạo mới sắp xuất hiện").assertIsDisplayed()
        compose.onNodeWithTag("nav_CONNECTION").performClick()
        compose.onNodeWithText("Sẵn sàng cho lần thử.").assertIsDisplayed()
    }

    @Test fun connectionDraftAndWardrobeTabSurviveNavigationAndActivityRecreation() {
        compose.onNodeWithTag("nav_CONNECTION").performClick()
        compose.onNodeWithTag("backend_url").performTextReplacement("https://example.invalid")
        compose.onNodeWithTag("nav_WARDROBE").performClick()
        compose.onNodeWithText("Kết quả · 0").performClick()
        compose.onNodeWithTag("nav_CONNECTION").performClick()
        compose.onNodeWithTag("backend_url").assertTextContains("https://example.invalid")
        compose.activityRule.scenario.recreate()
        compose.onNodeWithTag("backend_url").assertTextContains("https://example.invalid")
        compose.onNodeWithTag("nav_WARDROBE").performClick()
        compose.onNodeWithText("Diện mạo mới sắp xuất hiện").assertIsDisplayed()
    }
}
