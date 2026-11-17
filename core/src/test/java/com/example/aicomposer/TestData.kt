package com.example.aicomposer

import com.example.aicomposer.analysis.AnalysisResult
import com.example.aicomposer.analysis.CompositionAdvice
import com.example.aicomposer.analysis.CropPlan
import com.example.aicomposer.analysis.FaceInfo
import com.example.aicomposer.analysis.LumaStats
import com.example.aicomposer.analysis.NRect
import com.example.aicomposer.analysis.SceneType
import com.example.aicomposer.analysis.Subject
import com.example.aicomposer.coach.CoachInput
import com.example.aicomposer.camera.CameraState

/** Builds analysis results for tests (no real image needed). */
object TestData {
    fun luma(mean: Float = 0.45f, hi: Float = 0f, lo: Float = 0f, subj: Float? = null) =
        LumaStats(mean, hi, lo, subj)

    fun face(l: Float, t: Float, size: Float, yaw: Float = 0f, eyesOpen: Float = 0.9f, smile: Float = 0.5f) =
        FaceInfo(NRect(l, t, l + size / 1.5f, t + size), yaw, 0f, eyesOpen, eyesOpen, smile, emptyList())

    fun result(
        main: Subject? = null,
        faces: List<FaceInfo> = emptyList(),
        scene: SceneType = SceneType.GENERAL,
        tilt: Float? = null,
        luma: LumaStats = luma(),
        motion: Float = 0f,
        subjectMotion: Float? = null,
    ) = AnalysisResult(
        subjects = listOfNotNull(main), mainSubject = main, labels = emptyList(), scene = scene,
        tiltDeg = tilt, motion = motion, subjectMotion = subjectMotion, luma = luma,
        frameAspect = 1.5f, faces = faces,
    )

    fun input(
        a: AnalysisResult,
        comp: CompositionAdvice = CompositionAdvice(50, null, null, emptyList()),
        cam: CameraState = CameraState(),
        focal: Int = 50,
        plan: CropPlan = CropPlan(),
    ) = CoachInput(a, comp, plan, cam, null, focal, null)
}
