package com.trillboards.ctv.core.mdm

import android.content.Context
import android.util.Log
import com.trillboards.ctv.core.net.ApiClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * MDM orchestrator with state machine for device enrollment and policy management.
 *
 * State machine: UNENROLLED -> ENROLLING -> ENROLLED -> SUSPENDED
 *
 * On enrollment, starts periodic policy sync (every 5 min via coroutine).
 * All persistent state stored in SharedPreferences.
 */
class MdmClient(
    private val context: Context,
    private val apiClient: ApiClient
) {
    companion object {
        private const val TAG = "MdmClient"
        private const val MDM_PREFS = "trillboard_mdm_prefs"
        private const val PREF_ENROLLED = "mdm_enrolled"
        private const val PREF_ORG_ID = "mdm_org_id"
        private const val PREF_ENROLLMENT_CODE = "mdm_enrollment_code"
        private const val PREF_POLICIES_JSON = "mdm_policies_json"
        private const val PREF_STATE = "mdm_state"
        private const val PREF_SCREEN_MONGO_ID = "mdm_screen_mongo_id"
        private const val POLICY_SYNC_INTERVAL_MS = 5 * 60 * 1000L // 5 minutes
    }

    enum class MdmState {
        UNENROLLED,
        ENROLLING,
        ENROLLED,
        SUSPENDED
    }

    private val prefs = context.getSharedPreferences(MDM_PREFS, Context.MODE_PRIVATE)
    private val enrollmentManager = MdmEnrollmentManager(context, apiClient, prefs)
    private val policyEnforcer = MdmPolicyEnforcer(context)
    private val complianceReporter = MdmComplianceReporter(context)

    @Volatile
    private var currentState: MdmState = loadState()

    private var policySyncJob: Job? = null

    /**
     * Initialize MDM client. Restores state from SharedPreferences.
     * If already enrolled, starts policy sync immediately.
     */
    fun initialize(scope: CoroutineScope) {
        currentState = loadState()
        Log.i(TAG, "MDM client initialized, state=$currentState")
        if (currentState == MdmState.ENROLLED) {
            startPolicySync(scope)
            // Apply last known policies on startup
            val policiesJson = prefs.getString(PREF_POLICIES_JSON, null)
            if (policiesJson != null) {
                try {
                    policyEnforcer.applyPolicies(JSONObject(policiesJson))
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to apply cached policies on startup", e)
                }
            }
        }
    }

    /**
     * Enroll this device with an organization using an enrollment code.
     *
     * @param orgId Organization ID
     * @param enrollmentCode Enrollment code from the portal
     * @param screenMongoId The screen's MongoDB ID
     * @param fingerprint Device fingerprint for authentication
     * @param scope Coroutine scope for starting policy sync on success
     * @return true if enrollment succeeded
     */
    suspend fun enroll(
        orgId: String,
        enrollmentCode: String,
        screenMongoId: String,
        fingerprint: String,
        scope: CoroutineScope
    ): Boolean {
        if (currentState == MdmState.ENROLLED) {
            Log.w(TAG, "Already enrolled in org=${getOrgId()}")
            return true
        }

        setState(MdmState.ENROLLING)
        val success = enrollmentManager.enrollWithCode(orgId, enrollmentCode, screenMongoId, fingerprint)

        if (success) {
            setState(MdmState.ENROLLED)
            startPolicySync(scope)
            // Immediately fetch policies after enrollment
            syncPolicies()
            Log.i(TAG, "Enrollment successful for org=$orgId")
        } else {
            setState(MdmState.UNENROLLED)
            Log.w(TAG, "Enrollment failed for org=$orgId")
        }

        return success
    }

    /**
     * Enroll via QR code data. Parses the URL and delegates to enroll().
     *
     * @param qrData URL string like "https://app.trillboards.com/enroll/{code}?org={orgId}"
     * @param screenMongoId The screen's MongoDB ID
     * @param fingerprint Device fingerprint for authentication
     * @param scope Coroutine scope for starting policy sync on success
     * @return true if enrollment succeeded
     */
    suspend fun enrollViaQrCode(
        qrData: String,
        screenMongoId: String,
        fingerprint: String,
        scope: CoroutineScope
    ): Boolean {
        val parsed = enrollmentManager.parseQrCode(qrData)
        if (parsed == null) {
            Log.w(TAG, "Failed to parse QR code data: $qrData")
            return false
        }
        return enroll(parsed.first, parsed.second, screenMongoId, fingerprint, scope)
    }

    /**
     * Unenroll this device from MDM management.
     * Clears all MDM state and stops policy sync.
     */
    fun unenroll() {
        Log.i(TAG, "Unenrolling device from MDM")
        stopPolicySync()
        enrollmentManager.unenroll()
        setState(MdmState.UNENROLLED)
    }

    /**
     * Suspend MDM management temporarily (e.g., device offline, org suspended).
     */
    fun suspend() {
        if (currentState == MdmState.ENROLLED) {
            setState(MdmState.SUSPENDED)
            stopPolicySync()
            Log.i(TAG, "MDM management suspended")
        }
    }

    /**
     * Resume MDM management after suspension.
     */
    fun resume(scope: CoroutineScope) {
        if (currentState == MdmState.SUSPENDED) {
            setState(MdmState.ENROLLED)
            startPolicySync(scope)
            Log.i(TAG, "MDM management resumed")
        }
    }

    /**
     * Handle a policy.push socket event. Applies policies immediately.
     */
    fun handlePolicyPush(payload: JSONObject) {
        val transformed = transformPoliciesToDomainKeyed(payload)
        Log.i(TAG, "Received policy push — ${transformed.length()} domains")
        storePolicies(transformed)
        policyEnforcer.applyPolicies(transformed)
    }

    /**
     * Manually trigger a policy sync from the server.
     */
    suspend fun syncPolicies() {
        val screenMongoId = prefs.getString(PREF_SCREEN_MONGO_ID, null)
        if (screenMongoId.isNullOrEmpty()) {
            Log.w(TAG, "Cannot sync policies - no screenMongoId stored")
            return
        }

        val response = apiClient.fetchDevicePolicies(screenMongoId)
        if (response != null) {
            val transformed = transformPoliciesToDomainKeyed(response)
            storePolicies(transformed)
            policyEnforcer.applyPolicies(transformed)
            Log.i(TAG, "Policy sync completed — ${transformed.length()} domains")
        } else {
            Log.w(TAG, "Policy sync failed - no response from server")
        }
    }

    /**
     * Evaluate local policy compliance without contacting the server.
     */
    fun evaluateCompliance(): List<MdmPolicyEnforcer.PolicyComplianceResult> {
        return policyEnforcer.evaluateLocally()
    }

    /**
     * Build a compliance snapshot for piggybacking on heartbeat.
     */
    fun buildComplianceSnapshot(): Map<String, Any?> {
        return complianceReporter.buildSnapshot()
    }

    // --- Query methods ---

    fun isEnrolled(): Boolean = currentState == MdmState.ENROLLED

    fun getState(): MdmState = currentState

    fun getOrgId(): String? = prefs.getString(PREF_ORG_ID, null)

    fun getResolvedPolicies(): JSONObject? {
        val json = prefs.getString(PREF_POLICIES_JSON, null) ?: return null
        return try {
            JSONObject(json)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse stored policies", e)
            null
        }
    }

    fun getPolicyEnforcer(): MdmPolicyEnforcer = policyEnforcer

    fun getComplianceReporter(): MdmComplianceReporter = complianceReporter

    // --- Internal ---

    private fun loadState(): MdmState {
        val stateName = prefs.getString(PREF_STATE, MdmState.UNENROLLED.name)
        return try {
            MdmState.valueOf(stateName ?: MdmState.UNENROLLED.name)
        } catch (e: IllegalArgumentException) {
            MdmState.UNENROLLED
        }
    }

    private fun setState(state: MdmState) {
        currentState = state
        prefs.edit()
            .putString(PREF_STATE, state.name)
            .putBoolean(PREF_ENROLLED, state == MdmState.ENROLLED)
            .apply()
        Log.d(TAG, "MDM state -> $state")
    }

    /**
     * Transform any policy format (server array or socket payload) to domain-keyed format
     * that MdmPolicyEnforcer expects: { "volume": {config}, "display": {config}, ... }
     */
    private fun transformPoliciesToDomainKeyed(raw: JSONObject): JSONObject {
        val result = JSONObject()
        // Try array format: { data: [...] } or { policies: [...] }
        val arr = raw.optJSONArray("data") ?: raw.optJSONArray("policies")
        if (arr != null) {
            for (i in 0 until arr.length()) {
                val p = arr.optJSONObject(i) ?: continue
                val type = p.optString("policy_type", "")
                val config = p.optJSONObject("config")
                if (type.isNotBlank() && config != null) result.put(type, config)
            }
            return result
        }
        // Already domain-keyed: { volume: {...}, display: {...} }
        val keys = raw.keys()
        while (keys.hasNext()) {
            val k = keys.next()
            if (k == "success" || k == "message" || k == "timestamp") continue
            raw.optJSONObject(k)?.let { result.put(k, it) }
        }
        return result
    }

    private fun storePolicies(policies: JSONObject) {
        prefs.edit().putString(PREF_POLICIES_JSON, policies.toString()).apply()
    }

    private fun startPolicySync(scope: CoroutineScope) {
        if (policySyncJob?.isActive == true) return
        policySyncJob = scope.launch {
            while (isActive) {
                delay(POLICY_SYNC_INTERVAL_MS)
                try {
                    syncPolicies()
                } catch (e: Exception) {
                    Log.w(TAG, "Policy sync cycle failed", e)
                }
            }
        }
        Log.d(TAG, "Policy sync started (interval=${POLICY_SYNC_INTERVAL_MS}ms)")
    }

    private fun stopPolicySync() {
        policySyncJob?.cancel()
        policySyncJob = null
        Log.d(TAG, "Policy sync stopped")
    }
}
