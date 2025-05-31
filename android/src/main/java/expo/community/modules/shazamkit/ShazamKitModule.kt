package expo.community.modules.shazamkit

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
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

    private lateinit var catalog: Catalog
    private var currentSession: StreamingSession? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var isRecording = false
    var job: Job? = null
    private var developerToken: String? = null
    private var currentPromise: Promise? = null


    suspend fun shazamStarter(promise: Promise) {
        try {
            Log.i("ShazamKit", "shazamStarter: Starting Shazam session")
            Log.i("ShazamKit", "shazamStarter: Catalog available: ${::catalog.isInitialized}")

            when (val result = ShazamKit.createStreamingSession(
                catalog,
                AudioSampleRateInHz.SAMPLE_RATE_48000,
                8192
            )) {
                is ShazamKitResult.Success -> {
                    Log.d("ShazamKit", "shazamStarter: Session created successfully")
                    currentSession = result.data
                    Log.d("ShazamKit", "shazamStarter: Current session set: ${currentSession != null}")
                    
                    CoroutineScope(Dispatchers.IO).launch {
                        Log.d("ShazamKit", "shazamStarter: Launching IO coroutine for startListening")
                        startListening(promise)
                    }
                }
                is ShazamKitResult.Failure -> {
                    val errorMessage = result.reason.message ?: "Unknown error creating session"
                    Log.e("ShazamKit", "shazamStarter: Failed to create session: $errorMessage")
                    Log.e("ShazamKit", "shazamStarter: Failure reason: ${result.reason}")
                    safeReject(promise, "SESSION_ERROR", errorMessage, null)
                    return
                }
            }
            Log.d("ShazamKit", "shazamStarter: Setting up recognition results collection")
            currentSession?.let { session ->
                Log.d("ShazamKit", "shazamStarter: Current session is not null, starting results collection")
                try {
                    session.recognitionResults().collect { result: MatchResult ->
                        Log.d("ShazamKit", "shazamStarter: Received recognition result: ${result::class.simpleName}")
                        try{
                            when (result) {
                                is MatchResult.Match -> {
                                    Log.d("ShazamKit", "shazamStarter: MATCH found with ${result.matchedMediaItems.size} items")
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
                                    Log.d("ShazamKit", "shazamStarter: Resolving promise with results: ${results.size} items")
                                    safeResolve(promise, results)
                                    cleanup()
                                }
                                is MatchResult.NoMatch -> {
                                    Log.d("ShazamKit", "shazamStarter: NoMatch result received")
                                    safeReject(promise, "NO_MATCH", "No match found", null)
                                    cleanup()
                                }
                                is MatchResult.Error -> {
                                    Log.e("ShazamKit", "shazamStarter: MatchResult Error: ${result.exception.message}")
                                    Log.e("ShazamKit", "shazamStarter: Exception details", result.exception)
                                    safeReject(promise, "MATCH_ERROR", result.exception.message, result.exception.cause)
                                    cleanup()
                                }
                            }
                        }catch (e: Exception){
                            Log.e("ShazamKit", "shazamStarter: Exception in result processing: ${e.message}", e)
                            e.message?.let { onError(it) }
                            cleanup()
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ShazamKit", "shazamStarter: Exception in recognition results collection: ${e.message}", e)
                    safeReject(promise, "RECOGNITION_ERROR", e.message, e)
                }
            } ?: run {
                Log.e("ShazamKit", "shazamStarter: Current session is null after creation - this should not happen")
                safeReject(promise, "SESSION_NULL", "Session is null after successful creation", null)
            }

        } catch (e: Exception) {
            Log.e("ShazamKit", "shazamStarter: Uncaught exception: ${e.message}", e)
            safeReject(promise, "SHAZAM_ERROR", e.message, e)
        }
    }

    override fun definition() = ModuleDefinition {
        Name("ExpoShazamKit")

        Log.i("ShazamKit", "MODULE INITIALIZATION - ShazamKit module is being initialized")

        Function("setDeveloperToken") { token: String ->
            Log.i("ShazamKit", "setDeveloperToken called with token: ${token.take(20)}...")
            developerToken = token
            
            // Recreate catalog with new token
            val tokenProvider = DeveloperTokenProvider {
                DeveloperToken(token)
            }
            catalog = ShazamKit.createShazamCatalog(tokenProvider)
            Log.i("ShazamKit", "Catalog recreated with new developer token")
        }


        Function("isAvailable") {
            Log.e("ShazamKit", "=== ISAVAILABLE CALLED ===")
            true
        }

        AsyncFunction("startListening") { promise: Promise ->
            Log.i("ShazamKit", "startListening called from React Native")
            
            if (currentPromise != null) {
                Log.w("ShazamKit", "startListening: Already have active promise, rejecting new request")
                promise.reject("ALREADY_LISTENING", "Already listening for Shazam matches", null)
                return@AsyncFunction
            }
            
            currentPromise = promise
            
            try {
                job = CoroutineScope(Dispatchers.Unconfined).launch {
                    Log.i("ShazamKit", "Coroutine launched, calling shazamStarter")
                    shazamStarter(promise)
                }
                Log.i("ShazamKit", "Coroutine created successfully")
            } catch (e: Exception) {
                Log.e("ShazamKit", "Error launching coroutine: ${e.message}", e)
                safeReject(promise, "COROUTINE_ERROR", e.message, e)
            }
        }

        AsyncFunction("stopListening") { promise: Promise ->
            Log.d("Shazam", "Stoplistening called from react native")
            stopShazamListening(promise)
        }
    }

    fun startListening(promise: Promise) {
        Log.d("ShazamKit", "startListening: Function called")
        try {
            Log.d("ShazamKit", "startListening: Current session: ${currentSession?.toString() ?: "null"}")
            if (currentSession == null) {
                Log.e("ShazamKit", "startListening: Current session is null, cannot start listening")
                safeReject(promise, "SESSION_NULL", "Current session is null", null)
                return
            }
            Log.d("ShazamKit", "startListening: Session validated, setting up audio recording")
            
            val audioSource = MediaRecorder.AudioSource.DEFAULT
            val audioFormat = AudioFormat.Builder().setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48_000).build()

            Log.d("ShazamKit", "startListening: Creating AudioRecord with source: $audioSource")
            audioRecord =
                AudioRecord.Builder().setAudioSource(audioSource).setAudioFormat(audioFormat)
                    .build()
            
            val bufferSize = AudioRecord.getMinBufferSize(
                48_000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            Log.d("ShazamKit", "startListening: Buffer size: $bufferSize")
            
            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e("ShazamKit", "startListening: AudioRecord failed to initialize, state: ${audioRecord?.state}")
                safeReject(promise, "AUDIO_INIT_ERROR", "AudioRecord failed to initialize", null)
                return
            }
            
            Log.d("ShazamKit", "startListening: Starting audio recording")
            audioRecord?.startRecording()
            
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e("ShazamKit", "startListening: Failed to start recording, state: ${audioRecord?.recordingState}")
                safeReject(promise, "RECORDING_START_ERROR", "Failed to start audio recording", null)
                return
            }
            
            isRecording = true
            Log.d("ShazamKit", "startListening: Creating and starting recording thread")
            recordingThread = Thread({
                val readBuffer = ByteArray(bufferSize)
                Log.d("ShazamKit", "Recording thread started")
                while (isRecording) {
                    try {
                        val actualRead = audioRecord!!.read(readBuffer, 0, bufferSize)
                        if (actualRead > 0) {
                            Log.v("ShazamKit", "Read $actualRead bytes from microphone")
                            currentSession?.matchStream(readBuffer, actualRead, System.currentTimeMillis())
                        } else {
                            Log.w("ShazamKit", "Audio read returned: $actualRead")
                        }
                    } catch (e: Exception) {
                        Log.e("ShazamKit", "Error in recording thread: ${e.message}", e)
                        isRecording = false
                    }
                }
                Log.d("ShazamKit", "Recording thread finished")
            }, "AudioRecorder Thread")
            recordingThread!!.start()
            Log.d("ShazamKit", "startListening: Recording thread started successfully")
        } catch (e: Exception) {
            Log.e("ShazamKit", "startListening: Exception: ${e.message}", e)
            safeReject(promise, "RECORDING_ERROR", e.message, e)
            e.message?.let { onError(it) }
        }
    }

    private fun cleanup() {
        Log.d("ShazamKit", "cleanup: Called")
        currentPromise = null
        
        if (audioRecord != null) {
            Log.d("ShazamKit", "cleanup: Stopping recording")
            isRecording = false;
            try {
                audioRecord!!.stop()
                audioRecord!!.release()
                Log.d("ShazamKit", "cleanup: AudioRecord stopped and released")
            } catch (e: Exception) {
                Log.e("ShazamKit", "cleanup: Error stopping AudioRecord: ${e.message}", e)
            }
            audioRecord = null
            recordingThread = null
            job?.cancel()
            Log.d("ShazamKit", "cleanup: Cleanup completed")
        } else {
            Log.d("ShazamKit", "cleanup: AudioRecord was null, nothing to stop")
        }
    }

    fun stopShazamListening(promise: Promise) {
        Log.d("ShazamKit", "stopShazamListening: Called")
        cleanup()
        promise.resolve(true)
    }

    private fun onError(message: String) {
        Log.d("ShazamError", message.toString())
    }

    private fun safeResolve(promise: Promise, result: Any?) {
        if (currentPromise == promise) {
            Log.d("ShazamKit", "safeResolve: Resolving promise")
            currentPromise = null
            promise.resolve(result)
        } else {
            Log.w("ShazamKit", "safeResolve: Promise already settled, ignoring")
        }
    }

    private fun safeReject(promise: Promise, code: String, message: String?, cause: Throwable?) {
        if (currentPromise == promise) {
            Log.d("ShazamKit", "safeReject: Rejecting promise with code: $code")
            currentPromise = null
            promise.reject(code, message, cause)
        } else {
            Log.w("ShazamKit", "safeReject: Promise already settled, ignoring")
        }
    }

    fun isAvailable(): Boolean {
        return true
    }
}
