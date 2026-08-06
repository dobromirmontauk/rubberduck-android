package com.montauk.voicecapture.settings

/**
 * Bead vn-edu.52: Settings' credential rows need a third state beyond
 * "configured"/"not configured". A dev build's baked-in
 * `local.properties`/`BuildConfig` fallback (see [AppSecretsStore.effectiveAssemblyKey],
 * [AppSecretsStore.effectiveAnthropicKey], [AppSecretsStore.effectiveGithubToken])
 * can make live transcription, word cloud/titles, or upload actively work
 * even though the user never entered a runtime key/token -- showing
 * "Not configured" there is truthful about `AppSecretsStore.user*`/
 * `userGithubToken` but misleading about whether the feature currently
 * works. [DEV_FALLBACK] is that third state.
 *
 * Pure so it's plain-JUnit testable without Robolectric --
 * [com.montauk.voicecapture.ui.ApiKeyManagementRow] and
 * [com.montauk.voicecapture.ui.UploadTokenRow] both key their label off of
 * it.
 */
enum class CredentialDisplayState { NOT_CONFIGURED, DEV_FALLBACK, CONFIGURED }

/**
 * [hasRuntimeValue] always wins ([CONFIGURED]) regardless of [hasDevFallback]
 * -- a user-entered key/token takes precedence over the dev-build fallback
 * (bead vn-edu.48's precedence, which this display logic doesn't change).
 */
fun credentialDisplayState(hasRuntimeValue: Boolean, hasDevFallback: Boolean): CredentialDisplayState = when {
    hasRuntimeValue -> CredentialDisplayState.CONFIGURED
    hasDevFallback -> CredentialDisplayState.DEV_FALLBACK
    else -> CredentialDisplayState.NOT_CONFIGURED
}
