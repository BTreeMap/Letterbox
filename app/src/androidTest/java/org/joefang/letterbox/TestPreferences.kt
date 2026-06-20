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
 * First-launch onboarding gates the main UI behind a network-consent screen, so
 * tests that exercise the main flow must mark onboarding complete before
 * launching [MainActivity]. This keeps the production first-run experience intact
 * while letting tests reach the screen under test deterministically.
 */
object TestPreferences {

    /**
     * Mark onboarding complete so [MainActivity] shows the main UI immediately.
     *
     * @param acceptedTerms whether to also grant Cloudflare WARP network consent.
     */
    suspend fun seedOnboarded(context: Context, acceptedTerms: Boolean = false) {
        UserPreferencesRepository(context).completeOnboarding(acceptedTerms)
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
class OnboardingRule(private val acceptedTerms: Boolean = false) : TestRule {
    override fun apply(base: Statement, description: Description): Statement =
        object : Statement() {
            override fun evaluate() {
                val context = ApplicationProvider.getApplicationContext<Context>()
                runBlocking { TestPreferences.seedOnboarded(context, acceptedTerms) }
                base.evaluate()
            }
        }
}
