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

    private lateinit var catalog: Catalog
    private var currentSession: StreamingSession? = null
    private var audioRecord: AudioRecord? = null
    private var recordingThread: Thread? = null
    private var isRecording = false
    var job: Job? = null


    suspend fun shazamStarter(promise: Promise) {
        try {
            android.util.Log.d("ShazamResult", "Starting Shazam session")

            when (val result = ShazamKit.createStreamingSession(
                catalog,
                AudioSampleRateInHz.SAMPLE_RATE_48000,
                8192
            )) {
                is ShazamKitResult.Success -> {
                    android.util.Log.d("ShazamResult", "Session created successfully")
                    currentSession = result.data
                    CoroutineScope(Dispatchers.IO).launch {
                        startListening(promise)
                    }
                }
                is ShazamKitResult.Failure -> {
                    val errorMessage = result.reason.message ?: "Unknown error creating session"
                    android.util.Log.e("ShazamResult", "Failed to create session: $errorMessage")
                    promise.reject("SESSION_ERROR", errorMessage, null)
                }
            }
            currentSession?.let {
                currentSession?.recognitionResults()?.collect { result: MatchResult ->
                    android.util.Log.d("ShazamResult", "FIRST LINE")
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
                                promise.reject("MatchResult Error", result.exception.message, result.exception.cause)
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
            android.util.Log.e("ShazamResult", "Error in shazamStarter: ${e.message}", e)
            promise.reject("SHAZAM_ERROR", e.message, e)
        }
    }

    override fun definition() = ModuleDefinition {
        Name("ExpoShazamKit")


        val tokenProvider = DeveloperTokenProvider {
            DeveloperToken("eyJhbGciOiJFUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6IlZINkM1V0pRQUwifQ.eyJpc3MiOiJWMzcyUzZHV1RUIiwiaWF0IjoxNzQ4NjIyMjAzLCJleHAiOjE3NDg2MjU4MDN9.V0yasE27235oLItDKBrWMzTy942VnpQBKhnljhTRxmzZFjg7MzgmVmvLSvmIBe3wJZg6b6rddVJiuokmPEwNgQ")
        }
        catalog = ShazamKit.createShazamCatalog(tokenProvider)


        Function("isAvailable") {
            true
        }

        AsyncFunction("startListening") { promise: Promise ->
            job = CoroutineScope(Dispatchers.Unconfined).launch {
                shazamStarter(promise)
            }
        }

        AsyncFunction("stopListening") { promise: Promise ->
            Log.d("Shazam", "Stoplistening called from react native")
            stopShazamListening(promise)
        }
    }

    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun startListening(promise: Promise) {
        Log.d("Shazam", "start Listening")
        try {
            Log.d("Shazam", "${currentSession.toString()} current session")
            if (currentSession == null) {
                return
            }
            Log.d("Shazam", "start Listening started")
            val audioSource = MediaRecorder.AudioSource.DEFAULT
            val audioFormat = AudioFormat.Builder().setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                .setEncoding(AudioFormat.ENCODING_PCM_16BIT).setSampleRate(48_000).build()

            audioRecord =
                AudioRecord.Builder().setAudioSource(audioSource).setAudioFormat(audioFormat)
                    .build()
            val bufferSize = AudioRecord.getMinBufferSize(
                48_000,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT
            )
            audioRecord?.startRecording()
            isRecording = true
            recordingThread = Thread({
                val readBuffer = ByteArray(bufferSize)
                while (isRecording) {
                    Log.d("Shazam", "Recording Works")
                    val actualRead = audioRecord!!.read(readBuffer, 0, bufferSize)
                    currentSession?.matchStream(readBuffer, actualRead, System.currentTimeMillis())
                }
            }, "AudioRecorder Thread")
            recordingThread!!.start()
        } catch (e: Exception) {
            Log.d("Shazam", "Recording Error ${e.toString()}")
            e.message?.let { onError(it) }
        }
    }

    fun stopShazamListening(promise: Promise) {
        Log.d("Shazam", "Stoplistening works ${audioRecord.toString()}")
        if (audioRecord != null) {
            isRecording = false;
            audioRecord!!.stop()
            audioRecord!!.release()
            audioRecord = null
            recordingThread = null
            job?.cancel()
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
