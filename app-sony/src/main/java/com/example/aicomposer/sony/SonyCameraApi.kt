package com.example.aicomposer.sony

import com.example.aicomposer.camera.CameraState
import com.example.aicomposer.i18n.tr
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class CameraApiException(val method: String, val code: Int, message: String) :
    IOException(tr("$method 失败（错误码 $code）：$message", "$method に失敗しました（エラーコード $code）：$message", "$method failed (error $code): $message"))

/** Sony Camera Remote API (JSON-RPC over HTTP). */
class SonyCameraApi(private val endpoint: String, private val client: OkHttpClient) {
    private val nextId = AtomicInteger(1)
    private val json = "application/json".toMediaType()

    suspend fun call(method: String, params: JSONArray = JSONArray(), version: String = "1.0"): JSONObject =
        withContext(Dispatchers.IO) {
            val body = JSONObject()
                .put("method", method)
                .put("params", params)
                .put("id", nextId.getAndIncrement())
                .put("version", version)
            val req = Request.Builder().url(endpoint)
                .post(body.toString().toRequestBody(json))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string() ?: throw IOException(tr("$method：空响应", "$method：応答が空です", "$method: empty response"))
                val obj = JSONObject(text)
                obj.optJSONArray("error")?.let { err ->
                    throw CameraApiException(method, err.optInt(0), err.optString(1))
                }
                obj
            }
        }

    suspend fun availableApis(): Set<String> =
        call("getAvailableApiList").getJSONArray("result").optJSONArray(0).toStrings().toSet()

    suspend fun startRecMode() { call("startRecMode") }
    suspend fun startLiveview(): String = call("startLiveview").getJSONArray("result").getString(0)
    suspend fun stopLiveview() { call("stopLiveview") }

    suspend fun takePicture() {
        try {
            call("actTakePicture")
        } catch (e: CameraApiException) {
            // 40403: the capture is still processing (long exposure etc.), just wait for the result
            if (e.code == 40403) call("awaitTakePicture") else throw e
        }
    }

    suspend fun getEvent(prev: CameraState): CameraState =
        parseEvent(call("getEvent", JSONArray().put(false)), prev)

    suspend fun setFNumber(v: String) { call("setFNumber", JSONArray().put(v)) }
    suspend fun setShutterSpeed(v: String) { call("setShutterSpeed", JSONArray().put(v)) }
    suspend fun setIso(v: String) { call("setIsoSpeedRate", JSONArray().put(v)) }
    suspend fun setExposureCompensation(index: Int) { call("setExposureCompensation", JSONArray().put(index)) }

    companion object {
        /** Parse by the "type" field rather than array index, so different camera models work. */
        fun parseEvent(resp: JSONObject, prev: CameraState): CameraState {
            var s = prev
            val result = resp.optJSONArray("result") ?: return s
            for (i in 0 until result.length()) {
                val objs: List<JSONObject> = when (val item = result.opt(i)) {
                    is JSONObject -> listOf(item)
                    is JSONArray -> (0 until item.length()).mapNotNull { item.optJSONObject(it) }
                    else -> emptyList()
                }
                for (o in objs) {
                    when (o.optString("type")) {
                        "availableApiList" ->
                            s = s.copy(availableApis = o.optJSONArray("names").toStrings().toSet())
                        "cameraStatus" ->
                            s = s.copy(status = o.optString("cameraStatus").ifEmpty { null })
                        "exposureMode" ->
                            s = s.copy(exposureMode = o.optString("currentExposureMode").ifEmpty { null })
                        "fNumber" -> s = s.copy(
                            fNumber = o.optString("currentFNumber").ifEmpty { null },
                            fNumberCandidates = o.optJSONArray("fNumberCandidates").toStrings()
                                .ifEmpty { s.fNumberCandidates },
                        )
                        "shutterSpeed" -> s = s.copy(
                            shutter = o.optString("currentShutterSpeed").ifEmpty { null },
                            shutterCandidates = o.optJSONArray("shutterSpeedCandidates").toStrings()
                                .ifEmpty { s.shutterCandidates },
                        )
                        "isoSpeedRate" -> s = s.copy(
                            iso = o.optString("currentIsoSpeedRate").ifEmpty { null },
                            isoCandidates = o.optJSONArray("isoSpeedRateCandidates").toStrings()
                                .ifEmpty { s.isoCandidates },
                        )
                        "exposureCompensation" -> s = s.copy(
                            evIndex = if (o.has("currentExposureCompensation")) o.optInt("currentExposureCompensation") else s.evIndex,
                            evMin = o.optInt("minExposureCompensation", s.evMin),
                            evMax = o.optInt("maxExposureCompensation", s.evMax),
                            evStep = o.optInt("stepIndexOfExposureCompensation", s.evStep),
                        )
                    }
                }
            }
            return s
        }
    }
}

internal fun JSONArray?.toStrings(): List<String> =
    if (this == null) emptyList() else (0 until length()).map { optString(it) }
