package com.example.aicomposer.camera

/** What kind of camera the coach is working with. Some advice differs (e.g. a phone has no mode dial). */
enum class CameraKind {
    /** Sony camera driven over Wi-Fi (Camera Remote API): mode dial, aperture, ISO, exposure compensation. */
    SONY_REMOTE,

    /** The phone's own camera: fixed aperture, automatic exposure, exposure compensation and zoom only. */
    PHONE,

    /** A photo that has already been taken (scoring app): advice is for editing or for next time. */
    PHOTO,
}

/**
 * Current camera settings. Any value the camera does not report stays null and the rules that need it are skipped.
 * For Sony cameras this is parsed from getEvent; the phone app fills it from Camera2 capture results.
 */
data class CameraState(
    val kind: CameraKind = CameraKind.SONY_REMOTE,
    val status: String? = null,
    val exposureMode: String? = null,
    val fNumber: String? = null,
    val fNumberCandidates: List<String> = emptyList(),
    val shutter: String? = null,
    val shutterCandidates: List<String> = emptyList(),
    val iso: String? = null,
    val isoCandidates: List<String> = emptyList(),
    val evIndex: Int? = null,
    val evMin: Int = 0,
    val evMax: Int = 0,
    val evStep: Int = 1,
    /** EV per exposure-compensation index when the camera states it directly (phones); overrides [evStep]. */
    val evStepEv: Double? = null,
    val availableApis: Set<String> = emptySet(),
) {
    val isPhone: Boolean get() = kind == CameraKind.PHONE

    /** Whether advice may refer to Sony / a6000 controls (mode dial, aperture, ISO, Eye AF...). */
    val isSony: Boolean get() = kind == CameraKind.SONY_REMOTE

    /** Exposure compensation in EV per index. Sony: step=1 means 1/3 EV, step=2 means 1/2 EV. */
    val evStepSize: Double get() = evStepEv ?: if (evStep == 2) 0.5 else 1.0 / 3.0
    val evValue: Double? get() = evIndex?.let { it * evStepSize }
}
