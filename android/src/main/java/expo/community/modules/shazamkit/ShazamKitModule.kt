package expo.community.modules.shazamkit

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import androidx.annotation.RequiresPermission
import androidx.core.app.ActivityCompat
import com.shazam.shazamkit.*
import expo.modules.kotlin.Promise
import expo.modules.kotlin.exception.Exceptions
import expo.modules.kotlin.modules.Module
import expo.modules.kotlin.modules.ModuleDefinition
import kotlinx.coroutines.*

class ShazamKitModule : Module() {
  // Grab a standard Android Context from the Expo app context.
  private val androidContext
    get() = appContext.reactContext ?: throw Exceptions.ReactContextLost()

  private var currentDeveloperToken: String? = null
  private lateinit var catalog: Catalog
  private var currentSession: StreamingSession? = null
  private var audioRecord: AudioRecord? = null
  private var recordingThread: Thread? = null
  private var isRecording = false
  private var job: Job? = null

  override fun definition() = ModuleDefinition {
    Name("ExpoShazamKit")

    // Dynamically set the token from JavaScript
    Function("setDeveloperToken") { token: String ->
      currentDeveloperToken = token
      // Re-initialize ShazamKit with a new DeveloperTokenProvider
      val tokenProvider = DeveloperTokenProvider {
        DeveloperToken(token)
      }
      catalog = ShazamKit.createShazamCatalog(tokenProvider)
    }

    Function("isAvailable") {
      true
    }

    AsyncFunction("startListening") { promise: Promise ->
      if (!checkPermission()) {
        promise.reject("ERR_PERMISSION", "Recording permission not granted", null)
        return@AsyncFunction
      }

      job = CoroutineScope(Dispatchers.Unconfined).launch {
        shazamStarter(promise)
      }
    }

    AsyncFunction("stopListening") { promise: Promise ->
      Log.d("Shazam", "stopListening called from react native")
      stopShazamListening(promise)
    }
  }

  private fun checkPermission(): Boolean {
    return ActivityCompat.checkSelfPermission(
      androidContext,
      Manifest.permission.RECORD_AUDIO
    ) == PackageManager.PERMISSION_GRANTED
  }

  @Suppress("TooGenericExceptionCaught")
  suspend fun shazamStarter(promise: Promise) {
    try {
      if (!this::catalog.isInitialized) {
        // If catalog not set, we have no token
        promise.reject("TOKEN_ERROR", "No developer token has been set", null)
        return
      }

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
          // Reject with code + message + cause
          promise.reject("SESSION_ERROR", result.reason.message, result.reason)
        }
      }

      currentSession?.let { session ->
        session.recognitionResults().collect { matchResult ->
          try {
            when (matchResult) {
              is MatchResult.Match -> {
                val results = matchResult.matchedMediaItems.map {
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
                promise.resolve(results)
                stopShazamListening(promise)
              }
              is MatchResult.NoMatch -> {
                // If you have NoMatchException : CodedException
                promise.reject(NoMatchException())
                stopShazamListening(promise)
              }
              is MatchResult.Error -> {
                promise.reject(
                  "MATCH_RESULT_ERROR",
                  matchResult.exception.message ?: "Unknown error",
                  matchResult.exception
                )
                stopShazamListening(promise)
              }
            }
          } catch (e: Exception) {
            onError(e.message.orEmpty())
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
      promise.reject("START_LISTENING_ERROR", e.message, e)
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
