package com.amurcanov.tgwsproxy

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertExists
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppUpdatePopupIssue139Test {
    @get:Rule
    val composeRule = createComposeRule()

    @Test
    fun disableRemindersStaysInPopupAndCallsItsAction() {
        var disabled = false
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MaterialTheme {
                UpdateAvailableDialog(
                    tagName = "v1.11.8",
                    apkSize = 20_000_000L,
                    onDismiss = {},
                    onLater = {},
                    onDownload = {},
                    onDisableReminders = { disabled = true },
                )
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.update_prompt_title, "v1.11.8"))
            .assertExists()
        composeRule.onNodeWithText(context.getString(R.string.update_disable_reminders))
            .assertExists().performClick()
        composeRule.runOnIdle { assertTrue(disabled) }
    }

    @Test
    fun laterAndDownloadRemainIndependentActions() {
        var later = false
        var download = false
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        composeRule.setContent {
            MaterialTheme {
                UpdateAvailableDialog(
                    tagName = "v1.11.8",
                    apkSize = 20_000_000L,
                    onDismiss = {},
                    onLater = { later = true },
                    onDownload = { download = true },
                    onDisableReminders = {},
                )
            }
        }
        composeRule.onNodeWithText(context.getString(R.string.update_later))
            .assertExists().performClick()
        composeRule.onNodeWithText(context.getString(R.string.update_download_install))
            .assertExists().performClick()
        composeRule.runOnIdle {
            assertTrue(later)
            assertTrue(download)
        }
    }
}
