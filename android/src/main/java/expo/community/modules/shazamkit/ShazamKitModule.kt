package expo.community.modules.shazamkit

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.annotation.WorkerThread
import androidx.core.app.ActivityCompat
import com.shazam.shazamkit.*
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.functions.Coroutine
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import java.nio.ByteBuffer
import java.util.*
import kotlinx.coroutines.*
import android.os.Process.*
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.lang.Exception


class ShazamKitModule : Module() {
  private val context
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  // Keep track of the current dev token, initially empty or from your plugin config if you want.
  private var currentDeveloperToken: String? = null

  private lateinit var catalog: Catalog
  private var currentSession: StreamingSession? = null
  private var audioRecord: AudioRecord? = null
  private var recordingThread: Thread? = null
  private var isRecording = false
  var job: Job? = null

  override fun definition() = ModuleDefinition {
    Name("ExpoShazamKit")

    // This function allows JS to provide a new token at runtime
    Function("setDeveloperToken") { token: String ->
      currentDeveloperToken = token
      // Re-initialize or update ShazamKit with the new token
      // Inside ShazamKitModule.kt
      val tokenProvider = ShazamDeveloperTokenProvider(appContext.context!!)
      catalog = ShazamKit.createShazamCatalog(tokenProvider)
    }

    // Example function to check availability
    Function("isAvailable") {
      true
    }

    AsyncFunction("startListening") { promise: Promise ->
      if (!checkPermission()) {
        promise.reject("ERR_PERMISSION", "Recording permission not granted")
        return@AsyncFunction
      }

      job = CoroutineScope(Dispatchers.Unconfined).launch {
        shazamStarter(promise)
      }
    }

    AsyncFunction("stopListening") { promise: Promise ->
      Log.d("Shazam", "Stoplistening called from react native")
      stopShazamListening(promise)
    }
  }

  private fun checkPermission(): Boolean {
    return ActivityCompat.checkSelfPermission(
      context,
      Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
  }

  suspend fun shazamStarter(promise: Promise) {
    try {
      if (!this::catalog.isInitialized) {
        // If no token set yet, reject or handle gracefully
        promise.reject("TOKEN_ERROR", "No developer token has been set")
        return
      }

      // Create session from catalog
      when (val result = ShazamKit.createStreamingSession(
        catalog,
        AudioSampleRateInHz.SAMPLE_RATE_48000,
        8192
      )) {
        is ShazamKitResult.Success -> {
          currentSession = result.data
          CoroutineScope(Dispatchers.IO).launch {
            startListening(promise)
          }
        }
        is ShazamKitResult.Failure -> {
          promise.reject("SESSION_ERROR", result.reason.message ?: "Unknown error")
        }
      }

      // Collect match results, etc.
      currentSession?.let { session ->
        session.recognitionResults().collect { result: MatchResult ->
          try{
            when (result) {
              is MatchResult.Match -> {
                android.util.Log.d("ShazamResult", "MATCH")
                val results = result.matchedMediaItems.map {
                  MatchedItem(
                    isrc = it.isrc,
                    title = it.title,
                    artist = it.artist,
                    shazamID = it.shazamID,
                    appleMusicID = it.appleMusicID,
                    appleMusicURL = it.appleMusicURL?.toString().orEmpty(),
                    artworkURL = it.artworkURL?.toString().orEmpty(),
                    genres = it.genres,
                    webURL = it.webURL?.toString().orEmpty(),
                    subtitle = it.subtitle,
                    videoURL = it.videoURL?.toString().orEmpty(),
                    explicitContent = it.explicitContent ?: false,
                    matchOffset = it.matchOffsetInMs?.toDouble() ?: 0.0
                  )
                }
                android.util.Log.d("ShazamResult", results.toString())
                promise.resolve(results)
                stopShazamListening(promise)
              }
              is MatchResult.NoMatch -> {
                android.util.Log.d("ShazamResult", "NoMatch")
                promise.reject(NoMatchException())
                stopShazamListening(promise)
              }
              is MatchResult.Error -> {
                android.util.Log.d("ShazamResult", result.exception.message.toString())
                promise.reject("MATCH_RESULT_ERROR", result.exception.message.orEmpty(), result.exception)
                stopShazamListening(promise)
              }
            }
          }catch (e: Exception){
            e.message?.let { onError(it) }
            stopShazamListening(promise)
          }
        }
      }
    } catch (e: Exception) {
      promise.reject("SHAZAM_ERROR", e.message, e)
    }
  }

  @RequiresPermission(Manifest.permission.RECORD_AUDIO)
  fun startListening(promise: Promise) {
    try {
      if (currentSession == null) return

      val audioFormat = AudioFormat.Builder()
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
        .setSampleRate(48000)
        .build()

      audioRecord = AudioRecord.Builder()
        .setAudioSource(MediaRecorder.AudioSource.DEFAULT)
        .setAudioFormat(audioFormat)
        .build()

      val bufferSize = AudioRecord.getMinBufferSize(
        48000,
        AudioFormat.CHANNEL_IN_MONO,
        AudioFormat.ENCODING_PCM_16BIT
      )
      audioRecord?.startRecording()
      isRecording = true
      recordingThread = Thread({
        val readBuffer = ByteArray(bufferSize)
        while (isRecording) {
          val actualRead = audioRecord!!.read(readBuffer, 0, bufferSize)
          currentSession?.matchStream(readBuffer, actualRead, System.currentTimeMillis())
        }
      }, "AudioRecorder Thread")
      recordingThread!!.start()
    } catch (e: Exception) {
      promise.reject("START_LISTENING_ERROR", e.message.orEmpty(), e)
    }
  }

  fun stopShazamListening(promise: Promise) {
    if (audioRecord != null) {
      isRecording = false
      audioRecord!!.stop()
      audioRecord!!.release()
      audioRecord = null
      recordingThread = null
      job?.cancel()
      promise.resolve(true)
    }
  }

  private fun onError(message: String) {
    Log.d("ShazamError", message)
  }
}
