package com.trillboards.ctv.core.consent

import android.app.Activity
import android.content.Context
import android.util.Log
import com.google.android.ump.ConsentInformation
import com.google.android.ump.ConsentRequestParameters
import com.google.android.ump.UserMessagingPlatform

/**
 * Wraps Google's User Messaging Platform (UMP) SDK to drive the
 * Play Store-compliant consent flow before any AdMob/IMA code runs.
 *
 * Required because we use [com.google.android.gms.ads.MobileAds.registerWebView]
 * to bridge native-app context into Google IMA running inside a WebView
 * (our primary VAST playback path). That bridge causes the Mobile Ads
 * SDK to enumerate installed apps for ad-targeting, which Play Policy
 * requires gated behind a Prominent Disclosure with affirmative consent.
 *
 * Lifecycle:
 *  1. [init] in BaseAgentActivity.onCreate (cheap — creates the
 *     ConsentInformation object).
 *  2. [requestConsentIfNeeded] before WebView setup — runs
 *     `requestConsentInfoUpdate` + `loadAndShowConsentFormIfRequired`,
 *     then invokes onComplete with the `canRequestAds()` result.
 *  3. [WebViewAdsRegistrar] checks [canRegisterWebView] before
 *     calling `MobileAds.registerWebView(webView)`.
 *  4. [showPrivacyOptionsForm] from the operator-PIN settings dialog
 *     for revocation (Google requires this re-entry point).
 *
 * Graceful degradation: on GMS-unavailable devices (e.g. Fire TV AOSP)
 * the underlying SDK calls throw, we catch them, and `canRegisterWebView`
 * returns false. The existing try/catch in `WebViewAdsRegistrar` already
 * handles MobileAds itself being absent.
 *
 * Required manifest meta-data per platform (different value per app):
 *   `<meta-data android:name="com.google.android.gms.ads.APPLICATION_ID"
 *               android:value="ca-app-pub-XXXXXXXXX~YYYYYYYYY" />`
 * UMP uses the App ID to fetch the publisher's configured consent
 * messages from AdMob's CDN.
 */
object ConsentManager {
    private const val TAG = "ConsentManager"

    @Volatile
    private var consentInformation: ConsentInformation? = null

    /** Cheap — call from BaseAgentActivity.onCreate. */
    fun init(context: Context) {
        if (consentInformation != null) return
        try {
            consentInformation = UserMessagingPlatform.getConsentInformation(context)
        } catch (t: Throwable) {
            Log.w(TAG, "UMP unavailable on this device: ${t.javaClass.simpleName}: ${t.message}")
        }
    }

    /**
     * Runs UMP's full consent-gathering flow. Invokes [onComplete] with
     * `true` if the SDK is allowed to request ads (consent granted or
     * not required by jurisdiction), `false` otherwise. Always calls
     * back exactly once, including on error paths.
     */
    fun requestConsentIfNeeded(activity: Activity, onComplete: (canRequestAds: Boolean) -> Unit) {
        val ci = consentInformation
        if (ci == null) {
            Log.w(TAG, "ConsentManager not initialized; treating as no-consent")
            onComplete(false)
            return
        }
        try {
            val params = ConsentRequestParameters.Builder().build()
            ci.requestConsentInfoUpdate(activity, params, {
                UserMessagingPlatform.loadAndShowConsentFormIfRequired(activity) { error ->
                    if (error != null) {
                        Log.w(TAG, "Consent form error: ${error.errorCode} ${error.message}")
                    }
                    val ok = canRegisterWebView()
                    Log.i(TAG, "Consent flow complete; canRegisterWebView=$ok")
                    onComplete(ok)
                }
            }, { error ->
                Log.w(TAG, "Consent info update failed: ${error.errorCode} ${error.message}")
                onComplete(false)
            })
        } catch (t: Throwable) {
            Log.w(TAG, "UMP requestConsentInfoUpdate threw: ${t.javaClass.simpleName}: ${t.message}")
            onComplete(false)
        }
    }

    /**
     * True iff Google Mobile Ads code (registerWebView, etc.) is allowed
     * to run right now. False before [requestConsentIfNeeded] completes
     * or in jurisdictions where consent has been declined.
     */
    fun canRegisterWebView(): Boolean = try {
        consentInformation?.canRequestAds() ?: false
    } catch (t: Throwable) {
        false
    }

    /**
     * True if Google requires us to surface a re-entry point so the user
     * can revoke or modify their consent. Required by Play Policy in
     * jurisdictions where the consent form was shown.
     */
    fun isPrivacyOptionsRequired(): Boolean = try {
        consentInformation?.privacyOptionsRequirementStatus ==
            ConsentInformation.PrivacyOptionsRequirementStatus.REQUIRED
    } catch (t: Throwable) {
        false
    }

    /** Re-show the consent form so the user can change their choice. */
    fun showPrivacyOptionsForm(activity: Activity, onDismissed: () -> Unit) {
        try {
            UserMessagingPlatform.showPrivacyOptionsForm(activity) { error ->
                if (error != null) {
                    Log.w(TAG, "Privacy options form error: ${error.errorCode} ${error.message}")
                }
                onDismissed()
            }
        } catch (t: Throwable) {
            Log.w(TAG, "showPrivacyOptionsForm threw: ${t.message}")
            onDismissed()
        }
    }
}
