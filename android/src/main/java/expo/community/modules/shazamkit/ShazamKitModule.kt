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
                    promise.reject("SESSION_ERROR", errorMessage, null)
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
                                    promise.resolve(results)
                                    stopShazamListening(promise)
                                }
                                is MatchResult.NoMatch -> {
                                    Log.d("ShazamKit", "shazamStarter: NoMatch result received")
                                    promise.reject(NoMatchException())
                                    stopShazamListening(promise)
                                }
                                is MatchResult.Error -> {
                                    Log.e("ShazamKit", "shazamStarter: MatchResult Error: ${result.exception.message}")
                                    Log.e("ShazamKit", "shazamStarter: Exception details", result.exception)
                                    promise.reject("MatchResult Error", result.exception.message, result.exception.cause)
                                    stopShazamListening(promise)
                                }
                            }
                        }catch (e: Exception){
                            Log.e("ShazamKit", "shazamStarter: Exception in result processing: ${e.message}", e)
                            e.message?.let { onError(it) }
                            stopShazamListening(promise)
                        }
                    }
                } catch (e: Exception) {
                    Log.e("ShazamKit", "shazamStarter: Exception in recognition results collection: ${e.message}", e)
                    promise.reject("RECOGNITION_ERROR", e.message, e)
                }
            } ?: run {
                Log.e("ShazamKit", "shazamStarter: Current session is null after creation - this should not happen")
                promise.reject("SESSION_NULL", "Session is null after successful creation", null)
            }

        } catch (e: Exception) {
            Log.e("ShazamKit", "shazamStarter: Uncaught exception: ${e.message}", e)
            promise.reject("SHAZAM_ERROR", e.message, e)
        }
    }

    override fun definition() = ModuleDefinition {
        Name("ExpoShazamKit")

        Log.i("ShazamKit", "MODULE INITIALIZATION - ShazamKit module is being initialized")

        val tokenProvider = DeveloperTokenProvider {
            DeveloperToken("eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6IlZINkM1V0pRQUwifQ.eyJpc3MiOiJWMzcyUzZHV1RUIiwiaWF0IjoxNzQ4NjI1NzU1LCJleHAiOjE3NDg2MjkzNTV9.DoXfpx5nEckXAnzrvByD5CRe5Xk0u56DzuK2puJu_7gVujzt9sz7Psh1K-U0Egw48Z0xLj4AILMdveQ2uqXv4g")
        }
        catalog = ShazamKit.createShazamCatalog(tokenProvider)


        Function("isAvailable") {
            true
        }

        AsyncFunction("startListening") { promise: Promise ->
            Log.i("ShazamKit", "startListening called from React Native")
            try {
                job = CoroutineScope(Dispatchers.Unconfined).launch {
                    Log.i("ShazamKit", "Coroutine launched, calling shazamStarter")
                    shazamStarter(promise)
                }
                Log.i("ShazamKit", "Coroutine created successfully")
            } catch (e: Exception) {
                Log.e("ShazamKit", "Error launching coroutine: ${e.message}", e)
                promise.reject("COROUTINE_ERROR", e.message, e)
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
                promise.reject("SESSION_NULL", "Current session is null", null)
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
                promise.reject("AUDIO_INIT_ERROR", "AudioRecord failed to initialize", null)
                return
            }
            
            Log.d("ShazamKit", "startListening: Starting audio recording")
            audioRecord?.startRecording()
            
            if (audioRecord?.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                Log.e("ShazamKit", "startListening: Failed to start recording, state: ${audioRecord?.recordingState}")
                promise.reject("RECORDING_START_ERROR", "Failed to start audio recording", null)
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
            promise.reject("RECORDING_ERROR", e.message, e)
            e.message?.let { onError(it) }
        }
    }

    fun stopShazamListening(promise: Promise) {
        Log.d("ShazamKit", "stopShazamListening: Called with audioRecord: ${audioRecord?.toString() ?: "null"}")
        if (audioRecord != null) {
            Log.d("ShazamKit", "stopShazamListening: Stopping recording")
            isRecording = false;
            try {
                audioRecord!!.stop()
                audioRecord!!.release()
                Log.d("ShazamKit", "stopShazamListening: AudioRecord stopped and released")
            } catch (e: Exception) {
                Log.e("ShazamKit", "stopShazamListening: Error stopping AudioRecord: ${e.message}", e)
            }
            audioRecord = null
            recordingThread = null
            job?.cancel()
            Log.d("ShazamKit", "stopShazamListening: Cleanup completed")
            promise.resolve(true)
        } else {
            Log.d("ShazamKit", "stopShazamListening: AudioRecord was null, nothing to stop")
            promise.resolve(true)
        }
    }

    private fun onError(message: String) {
        Log.d("ShazamError", message.toString())
    }

    fun isAvailable(): Boolean {
        return true
    }
}
