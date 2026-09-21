package com.copy.account.page

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.copy.account.data.model.initialAccounts
import com.copy.account.data.model.initialGroups
import com.copy.account.ui.theme.AccountTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class HomeScreenBackTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun backCancelsBatchGroupMoveBeforeLeavingHome() {
        composeRule.setContent { HomeScreenUnderTest() }

        composeRule.onNodeWithText("默认").performTouchInput { longClick() }
        composeRule.onNodeWithText("从「默认」转移 · 已选 0 个").assertExists()

        composeRule.activity.onBackPressedDispatcher.onBackPressed()

        composeRule.onNodeWithText("从「默认」转移 · 已选 0 个").assertDoesNotExist()
    }

    @Test
    fun backClosesSearchAndClearsItsFilter() {
        composeRule.setContent { HomeScreenUnderTest() }

        composeRule.onNodeWithText("⌕").performClick()
        composeRule.onNodeWithText("搜索账号").assertExists()
        composeRule.onNode(hasSetTextAction()).performTextInput("普通")
        composeRule.onNodeWithText("搜索「普通」 · 1 条").assertExists()

        composeRule.activity.onBackPressedDispatcher.onBackPressed()

        composeRule.onNodeWithText("搜索账号").assertDoesNotExist()
        composeRule.onNodeWithText("默认 · 1 条").assertExists()
    }

    @androidx.compose.runtime.Composable
    private fun HomeScreenUnderTest() {
        AccountTheme {
            HomeScreen(
                accounts = initialAccounts,
                groups = initialGroups,
                selectedGroupId = "default",
                clipboardClearSeconds = 30,
                onGroupSelected = {},
                onNewAccount = {},
                onEditAccount = {},
                onTemplateNew = {},
                onDeleteAccount = {},
                onMoveAccounts = { _, _, _ -> },
                onManageGroups = {},
                onOpenSettings = {},
                onOpenDetail = {}
            )
        }
    }
}
