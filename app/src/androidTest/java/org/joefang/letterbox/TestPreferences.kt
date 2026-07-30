package org.joefang.letterbox

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.runBlocking
import org.joefang.letterbox.data.UserPreferencesRepository
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Shared instrumented-test helpers for seeding user preferences.
 *
 * First-launch onboarding shows an introduction ahead of the main UI, so tests
 * that exercise the main flow must mark it complete before launching
 * [MainActivity]. Onboarding grants no capability — it records only that the
 * introduction has been seen — so seeding it cannot change what a test observes
 * about image loading.
 */
object TestPreferences {

    /** Mark onboarding complete so [MainActivity] shows the main UI immediately. */
    suspend fun seedOnboarded(context: Context) {
        UserPreferencesRepository(context).completeOnboarding()
    }
}

/**
 * JUnit rule that marks onboarding complete *before* the activity under test is
 * launched. Chain it outside an activity rule:
 *
 * ```kotlin
 * val composeTestRule = createAndroidComposeRule<MainActivity>()
 * @get:Rule val rules = RuleChain.outerRule(OnboardingRule()).around(composeTestRule)
 * ```
 */
class OnboardingRule : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val context = ApplicationProvider.getApplicationContext<Context>()
                runBlocking { TestPreferences.seedOnboarded(context) }
                base.evaluate()
            }
        }
}
