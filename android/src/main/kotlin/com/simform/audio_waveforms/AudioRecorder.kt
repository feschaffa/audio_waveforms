package com.simform.audio_waveforms

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.media.MediaMetadataRetriever
import android.media.MediaMetadataRetriever.METADATA_KEY_DURATION
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.core.app.ActivityCompat
import com.simform.audio_waveforms.Constants.LOG_TAG
import io.flutter.plugin.common.MethodChannel.Result
import io.flutter.plugin.common.PluginRegistry

/**
 * MediaRecorder-based Android audio recorder.
 *
 * Deliberately ported from the pre-2.0.0 plugin architecture (MediaRecorder,
 * which handles mic capture, encoding and muxing internally as a single OS
 * call) rather than the AudioRecord+manual-MediaCodec rewrite introduced in
 * 2.0.0. That rewrite was the source of every native recording crash found
 * in production use of this app: a buffer-index leak, a stop()-vs-encoder
 * -completion-callback race, and a 0-duration timestamp bug - all inherent
 * to hand-rolling the MediaCodec async buffer queue. Recording is this app's
 * core flow, so it stays on the API Android itself manages end-to-end.
 */
class AudioRecorder : PluginRegistry.RequestPermissionsResultListener {
    private var permissions = arrayOf(Manifest.permission.RECORD_AUDIO)
    private var recorder: MediaRecorder? = null
    private var recorderSettings: RecorderSettings? = null
    private var successCallback: RequestPermissionsSuccessCallback? = null

    fun initRecorder(recorderSettings: RecorderSettings, result: Result) {
        val path = recorderSettings.path
        if (path == null) {
            result.error(LOG_TAG, "Recording path can't be null", null)
            return
        }
        this.recorderSettings = recorderSettings
        // WAV (raw PCM) has no MediaRecorder equivalent - it only exists via
        // manual MediaCodec encoding, which this recorder doesn't use.
        val encoder = if (recorderSettings.encoder == Encoder.WAV) {
            Log.e(LOG_TAG, "WAV is not supported by this recorder. Falling back to AAC_LC.")
            Encoder.AAC_LC
        } else {
            recorderSettings.encoder
        }
        try {
            recorder = MediaRecorder().apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(encoder.toOutputFormat)
                setAudioEncoder(encoder.toAudioEncoder)
                setAudioSamplingRate(recorderSettings.sampleRate)
                setAudioEncodingBitRate(recorderSettings.bitRate)
                setOutputFile(path)
                prepare()
            }
            result.success(true)
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Failed to initialise recorder: ${e.message}")
            recorder?.release()
            recorder = null
            result.error(LOG_TAG, "Failed to initialise recorder", e.message)
        }
    }

    fun start(result: Result) {
        try {
            recorder?.start()
            result.success(true)
        } catch (e: IllegalStateException) {
            Log.e(LOG_TAG, "Failed to start recording: ${e.message}")
            result.error(LOG_TAG, "Failed to start recording", e.message)
        }
    }

    fun stop(result: Result) {
        val path = recorderSettings?.path
        val hashMap: HashMap<String, Any?> = HashMap()
        try {
            recorder?.stop()
            hashMap[Constants.resultFilePath] = path
            hashMap[Constants.resultDuration] = getDuration(path)
        } catch (e: RuntimeException) {
            // stop() called immediately after start() throws - there's no
            // usable file in that case.
            Log.e(LOG_TAG, "Failed to stop recording: ${e.message}")
            hashMap[Constants.resultFilePath] = null
            hashMap[Constants.resultDuration] = -1
        }
        release()
        result.success(hashMap)
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun pause(result: Result) {
        try {
            recorder?.pause()
            result.success(false)
        } catch (e: IllegalStateException) {
            Log.e(LOG_TAG, "Failed to pause recording: ${e.message}")
            result.error(LOG_TAG, "Failed to pause recording", e.message)
        }
    }

    @RequiresApi(Build.VERSION_CODES.N)
    fun resume(result: Result) {
        try {
            recorder?.resume()
            result.success(true)
        } catch (e: IllegalStateException) {
            Log.e(LOG_TAG, "Failed to resume recording: ${e.message}")
            result.error(LOG_TAG, "Failed to resume recording", e.message)
        }
    }

    /** Polled from Dart at [RecorderController.updateFrequency] to drive the live waveform. */
    fun getDecibel(result: Result) {
        result.success(recorder?.maxAmplitude?.toDouble() ?: 0.0)
    }

    fun release() {
        try {
            recorder?.reset()
            recorder?.release()
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error releasing recorder: ${e.message}")
        }
        recorder = null
    }

    private fun getDuration(path: String?): Int {
        if (path == null) return -1
        val mediaMetadataRetriever = MediaMetadataRetriever()
        try {
            mediaMetadataRetriever.setDataSource(path)
            val duration = mediaMetadataRetriever.extractMetadata(METADATA_KEY_DURATION)
            return duration?.toInt() ?: -1
        } catch (e: Exception) {
            Log.e(LOG_TAG, "Error getting duration: ${e.message}")
        } finally {
            mediaMetadataRetriever.release()
        }
        return -1
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ): Boolean {
        return if (requestCode == Constants.RECORD_AUDIO_REQUEST_CODE) {
            successCallback?.onSuccess(grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED)
            grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED
        } else {
            false
        }
    }

    private fun isPermissionGranted(activity: Activity?): Boolean {
        val result = ActivityCompat.checkSelfPermission(activity!!, permissions[0])
        return result == PackageManager.PERMISSION_GRANTED
    }

    fun checkPermission(
        result: Result, activity: Activity?, successCallback: RequestPermissionsSuccessCallback
    ) {
        this.successCallback = successCallback
        if (!isPermissionGranted(activity)) {
            activity?.let {
                ActivityCompat.requestPermissions(
                    it, permissions, Constants.RECORD_AUDIO_REQUEST_CODE
                )
            }
        } else {
            result.success(true)
        }
    }
}
