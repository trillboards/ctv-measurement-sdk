package com.trillboards.ctv.core.mdm

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.trillboards.ctv.core.net.ApiClient
import org.json.JSONObject
import java.net.URI

/**
 * Manages MDM enrollment operations: enroll with code, enroll via QR, unenroll.
 * Uses ApiClient for network calls and SharedPreferences for state persistence.
 */
class MdmEnrollmentManager(
    private val context: Context,
    private val apiClient: ApiClient,
    private val prefs: SharedPreferences
) {
    companion object {
        private const val TAG = "MdmEnrollmentManager"
        private const val PREF_ENROLLED = "mdm_enrolled"
        private const val PREF_ORG_ID = "mdm_org_id"
        private const val PREF_ENROLLMENT_CODE = "mdm_enrollment_code"
        private const val PREF_POLICIES_JSON = "mdm_policies_json"
        private const val PREF_STATE = "mdm_state"
        private const val PREF_SCREEN_MONGO_ID = "mdm_screen_mongo_id"
        private const val PREF_DEVICE_TOKEN = "mdm_device_token"
    }

    /**
     * Enroll the device with an enrollment code.
     *
     * @param orgId Organization ID
     * @param enrollmentCode Enrollment code from the admin portal
     * @param screenMongoId The screen's MongoDB ID
     * @param fingerprint Device fingerprint for the enrollment request
     * @return true if enrollment succeeded
     */
    suspend fun enrollWithCode(
        orgId: String,
        enrollmentCode: String,
        screenMongoId: String,
        fingerprint: String
    ): Boolean {
        Log.i(TAG, "Enrolling device: org=$orgId, screenMongoId=$screenMongoId")

        val response = apiClient.enrollDevice(orgId, enrollmentCode, screenMongoId, fingerprint)
        if (response == null) {
            Log.w(TAG, "Enrollment API call returned null")
            return false
        }

        val success = response.optBoolean("success", false) ||
            response.optBoolean("status", false) ||
            response.optInt("statusCode", 0) in 200..299

        if (!success) {
            val error = response.optString("error", response.optString("message", "Unknown error"))
            Log.w(TAG, "Enrollment rejected: $error")
            return false
        }

        // Persist enrollment data
        val deviceToken = response.optString("deviceToken", "").ifEmpty { null }
        val data = response.optJSONObject("data")
        val resolvedOrgId = data?.optString("orgId", orgId) ?: orgId

        prefs.edit()
            .putBoolean(PREF_ENROLLED, true)
            .putString(PREF_ORG_ID, resolvedOrgId)
            .putString(PREF_ENROLLMENT_CODE, enrollmentCode)
            .putString(PREF_SCREEN_MONGO_ID, screenMongoId)
            .putString(PREF_STATE, MdmClient.MdmState.ENROLLED.name)
            .apply()

        if (deviceToken != null) {
            prefs.edit().putString(PREF_DEVICE_TOKEN, deviceToken).apply()
        }

        // Store initial policies if returned in enrollment response
        val policies = data?.optJSONObject("policies")
            ?: response.optJSONObject("policies")
        if (policies != null) {
            prefs.edit().putString(PREF_POLICIES_JSON, policies.toString()).apply()
        }

        Log.i(TAG, "Enrollment persisted: org=$resolvedOrgId, hasToken=${deviceToken != null}")
        return true
    }

    /**
     * Parse a QR code URL into (orgId, enrollmentCode) pair.
     * Expected URL format: https://app.trillboards.com/enroll/{code}?org={orgId}
     *
     * @param qrData Raw QR code string
     * @return Pair of (orgId, enrollmentCode) or null if parsing failed
     */
    fun parseQrCode(qrData: String): Pair<String, String>? {
        return try {
            val uri = URI(qrData.trim())
            val path = uri.path ?: return null

            // Extract enrollment code from path: /enroll/{code}
            val pathSegments = path.split("/").filter { it.isNotEmpty() }
            if (pathSegments.size < 2 || pathSegments[0] != "enroll") {
                Log.w(TAG, "QR code path does not match /enroll/{code}: $path")
                return null
            }
            val enrollmentCode = pathSegments[1]

            // Extract orgId from query parameter
            val query = uri.query ?: ""
            val params = query.split("&").associate { param ->
                val parts = param.split("=", limit = 2)
                if (parts.size == 2) parts[0] to parts[1] else parts[0] to ""
            }
            val orgId = params["org"]
            if (orgId.isNullOrEmpty()) {
                Log.w(TAG, "QR code missing org query parameter: $qrData")
                return null
            }

            Log.d(TAG, "Parsed QR code: org=$orgId, code=$enrollmentCode")
            Pair(orgId, enrollmentCode)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse QR code URL: $qrData", e)
            null
        }
    }

    /**
     * Unenroll the device: clear all MDM-related SharedPreferences.
     */
    fun unenroll() {
        Log.i(TAG, "Clearing all MDM enrollment data")
        prefs.edit()
            .remove(PREF_ENROLLED)
            .remove(PREF_ORG_ID)
            .remove(PREF_ENROLLMENT_CODE)
            .remove(PREF_POLICIES_JSON)
            .remove(PREF_STATE)
            .remove(PREF_SCREEN_MONGO_ID)
            .remove(PREF_DEVICE_TOKEN)
            .apply()
    }
}
