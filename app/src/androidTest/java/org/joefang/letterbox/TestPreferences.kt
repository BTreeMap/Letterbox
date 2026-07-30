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

    /**
     * Put preferences into the state a test needs before [MainActivity] launches:
     * onboarding done, and no update check due.
     *
     * The update stamp is not incidental. `MainActivity` runs a throttled update
     * check on launch, and a fresh install has `lastUpdateCheck = 0`, so the
     * throttle never fires — meaning every test launch would provision a WARP
     * device, bring up the tunnel and round-trip to GitHub before the UI settles.
     * Stamping "checked just now" makes the existing throttle suppress it.
     *
     * This used to be masked: the check was gated on a consent flag that tests
     * left false, so it never ran and nothing recorded why that mattered.
     */
    suspend fun seedOnboarded(context: Context) {
        val repository = UserPreferencesRepository(context)
        repository.completeOnboarding()
        repository.setLastUpdateCheck(System.currentTimeMillis())
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
