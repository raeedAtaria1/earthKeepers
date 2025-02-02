/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.mediapipe.examples.gesturerecognizer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import androidx.camera.core.ImageProxy
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.MPImage
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizerResult
import com.google.mediapipe.examples.gesturerecognizer.fragment.CameraFragment
import android.os.Environment
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.Date
import com.google.mediapipe.examples.gesturerecognizer.Constants.LABELS_PATH
import com.google.mediapipe.examples.gesturerecognizer.Constants.MODEL_PATH
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore
import com.google.mediapipe.examples.gesturerecognizer.util.TextToSpeechHelper

class GestureRecognizerHelper(
    var minHandDetectionConfidence: Float = DEFAULT_HAND_DETECTION_CONFIDENCE,
    var minHandTrackingConfidence: Float = DEFAULT_HAND_TRACKING_CONFIDENCE,
    var minHandPresenceConfidence: Float = DEFAULT_HAND_PRESENCE_CONFIDENCE,
    var currentDelegate: Int = DELEGATE_CPU,
    var runningMode: RunningMode = RunningMode.IMAGE,
    val context: Context,
    val gestureRecognizerListener: GestureRecognizerListener? = null

) {
    // Points assignment function
    private val classCountMap = mutableMapOf<String, Int>()
    private var totalPoints = 0
    private var lastCaptureTime: Long = 0
    private val captureDelay = 1000 // 1 seconds delay
    private var fivePhotos=0
    val detectedObjectsList = mutableListOf<DetectedObject>()

    // Points assignment function
    data class DetectedObject(var className: String, var count: Int = 0)

    private fun getPointsForClass(clsName: String): Int {
        return when (clsName) {
            "Can" -> 10
            "Glass Bottle" -> 12
            "Paper Cup" -> 5
            "Peel" -> 3
            "Plastic Bag" -> 15
            "Plastic Bottle" -> 10
            "Plastic Cup" -> 8
            "Snack" -> 7
            "Tissue" -> 2
            else -> 0
        }
    }

    // Function to update user points in Firestore
    private fun updateUserPoints(points: Int, clsName: String) {
        val userId = getCurrentUserId()
        val firestore = FirebaseFirestore.getInstance()
        val userRef = firestore.collection("users").document(userId)
        lateinit var textToSpeechHelper: TextToSpeechHelper
        textToSpeechHelper = TextToSpeechHelper(context) {
            Log.d("TextToSpeechHelper", "TextToSpeech initialized")
        }

        userRef.get()
            .addOnSuccessListener { document ->
                if (document != null && document.exists()) {
                    val currentPoints = document.getLong("points")?.toInt() ?: 0
                    val newPoints = currentPoints + points
                    userRef.update("points", newPoints)
                        .addOnSuccessListener {
                            Log.d("GestureRecognizer", "Points updated successfully")
                            // Speak the points
                            textToSpeechHelper.speak("$clsName $points points added")
                        }
                        .addOnFailureListener { e ->
                            Log.e("GestureRecognizer", "Error updating points", e)
                        }
                }
            }
            .addOnFailureListener { e ->
                Log.e("GestureRecognizer", "Error fetching user data", e)
            }
        // Update session data
        classCountMap[clsName] = classCountMap.getOrDefault(clsName, 0) + 1
        totalPoints += points
    }

    // Function to get session summary
    fun getSessionSummary(): Map<String, Int> {
        return classCountMap
    }

    // Function to get total points
    fun getTotalPoints(): Int {
        return totalPoints
    }

    // Function to get the current user ID
    private fun getCurrentUserId(): String {
        return FirebaseAuth.getInstance().currentUser?.uid ?: ""
    }

    // For this example this needs to be a var so it can be reset on changes. If the GestureRecognizer
    // will not change, a lazy val would be preferable.
    private var gestureRecognizer: GestureRecognizer? = null
    private lateinit var objectDetector: ObjectDetector

    init {
        setupGestureRecognizer()
    }

    fun clearGestureRecognizer() {
        gestureRecognizer?.close()
        gestureRecognizer = null
    }

    // Initialize the gesture recognizer using current settings on the
    // thread that is using it. CPU can be used with recognizers
    // that are created on the main thread and used on a background thread, but
    // the GPU delegate needs to be used on the thread that initialized the recognizer
    fun setupGestureRecognizer() {
        // Set general recognition options, including number of used threads
        val baseOptionBuilder = BaseOptions.builder()

        // Use the specified hardware for running the model. Default to CPU
        when (currentDelegate) {
            DELEGATE_CPU -> {
                baseOptionBuilder.setDelegate(Delegate.CPU)
            }
            DELEGATE_GPU -> {
                baseOptionBuilder.setDelegate(Delegate.GPU)
            }
        }

        baseOptionBuilder.setModelAssetPath(MP_RECOGNIZER_TASK)

        try {
            val baseOptions = baseOptionBuilder.build()
            val optionsBuilder =
                GestureRecognizer.GestureRecognizerOptions.builder()
                    .setBaseOptions(baseOptions)
                    .setMinHandDetectionConfidence(minHandDetectionConfidence)
                    .setMinTrackingConfidence(minHandTrackingConfidence)
                    .setMinHandPresenceConfidence(minHandPresenceConfidence)
                    .setRunningMode(runningMode)
                    .setNumHands(2)



            if (runningMode == RunningMode.LIVE_STREAM) {
                optionsBuilder
                    .setResultListener(this::returnLivestreamResult)
                    .setErrorListener(this::returnLivestreamError)
            }
            val options = optionsBuilder.build()
            gestureRecognizer =
                GestureRecognizer.createFromOptions(context, options)
        } catch (e: IllegalStateException) {
            gestureRecognizerListener?.onError(
                "Gesture recognizer failed to initialize. See error logs for " + "details"
            )
            Log.e(
                TAG,
                "MP Task Vision failed to load the task with error: " + e.message
            )
        } catch (e: RuntimeException) {
            gestureRecognizerListener?.onError(
                "Gesture recognizer failed to initialize. See error logs for " + "details",
                GPU_ERROR
            )
            Log.e(
                TAG,
                "MP Task Vision failed to load the task with error: " + e.message
            )
        }
    }

    // Convert the ImageProxy to MP Image and feed it to GestureRecognizer.
    fun recognizeLiveStream(
        imageProxy: ImageProxy,
    ) {
        val frameTime = SystemClock.uptimeMillis()

        // Copy out RGB bits from the frame to a bitmap buffer
        val bitmapBuffer = Bitmap.createBitmap(
            imageProxy.width, imageProxy.height, Bitmap.Config.ARGB_8888
        )
        imageProxy.use { bitmapBuffer.copyPixelsFromBuffer(imageProxy.planes[0].buffer) }
        imageProxy.close()

        val matrix = Matrix().apply {
            // Rotate the frame received from the camera to be in the same direction as it'll be shown
            postRotate(imageProxy.imageInfo.rotationDegrees.toFloat())

            // flip image since we only support front camera
            postScale(
                -1f, 1f, imageProxy.width.toFloat(), imageProxy.height.toFloat()
            )
        }

        // Rotate bitmap to match what our model expects
        val rotatedBitmap = Bitmap.createBitmap(
            bitmapBuffer,
            0,
            0,
            bitmapBuffer.width,
            bitmapBuffer.height,
            matrix,
            true
        )

        // Ensure the object was grabbed and enough time has passed since the last capture
        // Initialize a list to store detected object class names
        // Initialize a list to store detected object class names and counts

        if (CameraFragment.objectWasgrabbed || fivePhotos > 0) {
            if (CameraFragment.objectWasgrabbed) {
                fivePhotos = 3
            }

            CameraFragment.objectWasgrabbed = false

            val saveDirectory = File(context.getExternalFilesDir(null), "captured_images")
            if (!saveDirectory.exists()) {
                saveDirectory.mkdirs()
            }

            fivePhotos--

            val filename = "recognized_frame_${5 - fivePhotos}.jpg"
            val file = File(saveDirectory, filename)
            saveBitmap(rotatedBitmap, context, filename)

            lastCaptureTime = SystemClock.uptimeMillis()

            objectDetector = ObjectDetector(context, MODEL_PATH, LABELS_PATH, object : ObjectDetector.DetectorListener {
                override fun onEmptyDetect() {
                    Log.d(YOLO123, "No objects detected on attempt ${5 - fivePhotos}")
                }

                override fun onDetect(boundingBoxes: List<BoundingBox>, inferenceTime: Long) {
                    if (boundingBoxes.isEmpty()) {
                        Log.d(YOLO123, "Detected object with highest confidence on attempt ${3 - fivePhotos}: No objects detected")
                    } else {
                        // Find the object with the highest confidence in the current image
                        val maxConfidenceBox = boundingBoxes.maxByOrNull { it.cnf }
                        Log.d(YOLO123, "Detected object with highest confidence on attempt ${3 - fivePhotos}: ${maxConfidenceBox?.clsName} with confidence: ${maxConfidenceBox?.cnf}")

                        maxConfidenceBox?.let { box ->
                            // Check if the detected object already exists in the list
                            val detectedObject = detectedObjectsList.find { it.className == box.clsName }

                            if (detectedObject != null) {
                                // Increment the count if it exists
                                detectedObject.count++
                            } else {
                                // Add new object to the list with a count of 1
                                detectedObjectsList.add(DetectedObject(className = box.clsName, count = 1))
                            }

                            // Log the current list of detected objects with counts
                            Log.d(YOLO123, "Current detected objects list: $detectedObjectsList")
                        }
                    }
                }
            })

            objectDetector.runDetection(rotatedBitmap)

            if (fivePhotos == 0) {
                if (detectedObjectsList.isEmpty()) {
                    Log.d(YOLO123, "No objects were detected in any of the attempts.")
                } else {
                    // Determine the most frequently detected object manually
                    var mostFrequentObject: DetectedObject? = null

                    for (obj in detectedObjectsList) {
                        if (mostFrequentObject == null || obj.count > mostFrequentObject.count) {
                            mostFrequentObject = obj
                        }
                    }

                    if (mostFrequentObject != null) {
                        Log.d(YOLO123, "Most frequently detected object: ${mostFrequentObject.className} with count: ${mostFrequentObject.count}")

                        // Update user points for the detected object
                        val points = getPointsForClass(mostFrequentObject.className)
                        updateUserPoints(points, mostFrequentObject.className)
                    }
                }

                // Clear the detected objects list for the next round
                detectedObjectsList.clear()
            }
        }





        // Convert the input Bitmap object to an MPImage object to run inference
        val mpImage = BitmapImageBuilder(rotatedBitmap).build()

        recognizeAsync(mpImage, frameTime)
    }
    fun saveBitmap(bitmap: Bitmap, context: Context, fileName: String) {
        val date = Date()

        val dirPath = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES).toString()
        val file = File(dirPath)
        if (!file.exists()) {
            file.mkdir()
        }
        val path = "$dirPath/$fileName.jpeg"

        try {
            val imageUrl = File(path)
            FileOutputStream(imageUrl).use { out ->
                bitmap.compress(Bitmap.CompressFormat.JPEG, 100, out)
            }
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
    // Run hand gesture recognition using MediaPipe Gesture Recognition API
    @VisibleForTesting
    fun recognizeAsync(mpImage: MPImage, frameTime: Long) {
        // As we're using running mode LIVE_STREAM, the recognition result will
        // be returned in returnLivestreamResult function
        gestureRecognizer?.recognizeAsync(mpImage, frameTime)
    }

    // Accepts the URI for a video file loaded from the user's gallery and attempts to run
    // gesture recognizer inference on the video. This process will evaluate
    // every frame in the video and attach the results to a bundle that will be
    // returned.
    fun recognizeVideoFile(
        videoUri: Uri,
        inferenceIntervalMs: Long
    ): ResultBundle? {
        if (runningMode != RunningMode.VIDEO) {
            throw IllegalArgumentException(
                "Attempting to call recognizeVideoFile" +
                        " while not using RunningMode.VIDEO"
            )
        }

        // Inference time is the difference between the system time at the start and finish of the
        // process
        val startTime = SystemClock.uptimeMillis()

        var didErrorOccurred = false

        // Load frames from the video and run the gesture recognizer.
        val retriever = MediaMetadataRetriever()
        retriever.setDataSource(context, videoUri)
        val videoLengthMs =
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLong()

        // Note: We need to read width/height from frame instead of getting the width/height
        // of the video directly because MediaRetriever returns frames that are smaller than the
        // actual dimension of the video file.
        val firstFrame = retriever.getFrameAtTime(0)
        val width = firstFrame?.width
        val height = firstFrame?.height

        // If the video is invalid, returns a null recognition result
        if ((videoLengthMs == null) || (width == null) || (height == null)) return null

        // Next, we'll get one frame every frameInterval ms, then run recognizer
        // on these frames.
        val resultList = mutableListOf<GestureRecognizerResult>()
        val numberOfFrameToRead = videoLengthMs.div(inferenceIntervalMs)

        for (i in 0..numberOfFrameToRead) {
            val timestampMs = i * inferenceIntervalMs // ms

            retriever
                .getFrameAtTime(
                    timestampMs * 1000, // convert from ms to micro-s
                    MediaMetadataRetriever.OPTION_CLOSEST
                )
                ?.let { frame ->
                    // Convert the video frame to ARGB_8888 which is required by the MediaPipe
                    val argb8888Frame =
                        if (frame.config == Bitmap.Config.ARGB_8888) frame
                        else frame.copy(Bitmap.Config.ARGB_8888, false)

                    // Convert the input Bitmap object to an MPImage object to run inference
                    val mpImage = BitmapImageBuilder(argb8888Frame).build()

                    // Run gesture recognizer using MediaPipe Gesture Recognizer
                    // API
                    gestureRecognizer?.recognizeForVideo(mpImage, timestampMs)
                        ?.let { recognizerResult ->
                            resultList.add(recognizerResult)
                        } ?: {
                        didErrorOccurred = true
                        gestureRecognizerListener?.onError(
                            "ResultBundle could not be returned" +
                                    " in recognizeVideoFile"
                        )
                    }
                }
                ?: run {
                    didErrorOccurred = true
                    gestureRecognizerListener?.onError(
                        "Frame at specified time could not be" +
                                " retrieved when recognition in video."
                    )
                }
        }

        retriever.release()

        val inferenceTimePerFrameMs =
            (SystemClock.uptimeMillis() - startTime).div(numberOfFrameToRead)

        return if (didErrorOccurred) {
            null
        } else {
            ResultBundle(resultList, inferenceTimePerFrameMs, height, width)
        }
    }

    // Accepted a Bitmap and runs gesture recognizer inference on it to
    // return results back to the caller
    fun recognizeImage(image: Bitmap): ResultBundle? {
        if (runningMode != RunningMode.IMAGE) {
            throw IllegalArgumentException(
                "Attempting to call detectImage" +
                        " while not using RunningMode.IMAGE"
            )
        }


        // Inference time is the difference between the system time at the
        // start and finish of the process
        val startTime = SystemClock.uptimeMillis()

        // Convert the input Bitmap object to an MPImage object to run inference
        val mpImage = BitmapImageBuilder(image).build()

        // Run gesture recognizer using MediaPipe Gesture Recognizer API
        gestureRecognizer?.recognize(mpImage)?.also { recognizerResult ->
            val inferenceTimeMs = SystemClock.uptimeMillis() - startTime
            return ResultBundle(
                listOf(recognizerResult),
                inferenceTimeMs,
                image.height,
                image.width
            )
        }

        // If gestureRecognizer?.recognize() returns null, this is likely an error. Returning null
        // to indicate this.
        gestureRecognizerListener?.onError(
            "Gesture Recognizer failed to recognize."
        )
        return null
    }

    // Return running status of the recognizer helper
    fun isClosed(): Boolean {
        return gestureRecognizer == null
    }

    // Return the recognition result to the GestureRecognizerHelper's caller
    private fun returnLivestreamResult(
        result: GestureRecognizerResult, input: MPImage
    ) {
        val finishTimeMs = SystemClock.uptimeMillis()
        val inferenceTime = finishTimeMs - result.timestampMs()

        gestureRecognizerListener?.onResults(
            ResultBundle(
                listOf(result), inferenceTime, input.height, input.width
            )
        )
    }

    // Return errors thrown during recognition to this GestureRecognizerHelper's
    // caller
    private fun returnLivestreamError(error: RuntimeException) {
        gestureRecognizerListener?.onError(
            error.message ?: "An unknown error has occurred"
        )
    }

    companion object {
        val TAG = "GestureRecognizerHelper ${this.hashCode()}"
        val YOLO123="yolo"
        private const val MP_RECOGNIZER_TASK = "rock_paper_scissors (1).task"

        const val DELEGATE_CPU = 0
        const val DELEGATE_GPU = 1
        const val DEFAULT_HAND_DETECTION_CONFIDENCE = 0.5F
        const val DEFAULT_HAND_TRACKING_CONFIDENCE = 0.5F
        const val DEFAULT_HAND_PRESENCE_CONFIDENCE = 0.5F
        const val OTHER_ERROR = 0
        const val GPU_ERROR = 1
    }

    data class ResultBundle(
        val results: List<GestureRecognizerResult>,
        val inferenceTime: Long,
        val inputImageHeight: Int,
        val inputImageWidth: Int,
    )

    interface GestureRecognizerListener {
        fun onError(error: String, errorCode: Int = OTHER_ERROR)
        fun onResults(resultBundle: ResultBundle)
    }
}
