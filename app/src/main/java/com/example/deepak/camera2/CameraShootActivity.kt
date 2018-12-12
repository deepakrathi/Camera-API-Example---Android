package com.example.deepak.camera2

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.provider.MediaStore
import android.support.v4.content.FileProvider
import android.support.v7.app.AppCompatActivity
import android.util.SparseIntArray
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import kotlinx.android.synthetic.main.camera_shoot_layout.*
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.*


class CameraShootActivity : AppCompatActivity() {

    private val cameraManager by lazy {
        getSystemService(Context.CAMERA_SERVICE) as CameraManager
    }

    private val deviceStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice?) {
            cameraDevice = camera!!
            previewSession()
        }

        override fun onDisconnected(camera: CameraDevice?) {
            if (camera != null) {
                camera.close()
            }
        }

        override fun onError(camera: CameraDevice?, error: Int) {
            UtilsFunctions.eLog("error", error.toString())
        }

    }

    private lateinit var backgroundThread: HandlerThread
    private lateinit var backgroundHandler: Handler
    private lateinit var cameraDevice: CameraDevice
    private lateinit var captureSession: CameraCaptureSession
    private lateinit var captureRequest: CaptureRequest.Builder
    private lateinit var file: File
    private var croppedImage: String? = null
    private val PIC_CROP = 500
    private val GALLERY_IMAGE_REQUEST_CODE: Int = 501
    private var imageReader: ImageReader? = null
    var finger_spacing = 0f
    var zoom_level = 1
    var maximumZoomLevel: Float = 0f
    private var supportsFlash: Boolean = false
    private val flashOn: Boolean = false

    companion object {
        private var orientation = SparseIntArray()

    }

    private var width = 1280
    private var height = 720

    private var mState: Int = 0

    /**
     * Camera state: Showing camera preview.
     */
    private val STATE_PREVIEW = 0

    /**
     * Camera state: Waiting for the focus to be locked.
     */
    private val STATE_WAITING_LOCK = 1

    /**
     * Camera state: Waiting for the exposure to be precapture state.
     */
    private val STATE_WAITING_PRECAPTURE = 2

    /**
     * Camera state: Waiting for the exposure state to be something other than precapture.
     */
    private val STATE_WAITING_NON_PRECAPTURE = 3

    /**
     * Camera state: Picture was taken.
     */
    private val STATE_PICTURE_TAKEN = 4


    private val mCaptureCallback = object : CameraCaptureSession.CaptureCallback() {

        private fun process(result: CaptureResult) {
            when (mState) {
                STATE_PREVIEW -> {
                }// We have nothing to do when the camera preview is working normally.
                STATE_WAITING_LOCK -> {
                    val afState = result.get(CaptureResult.CONTROL_AF_STATE)
                    if (afState == null) {
                        captureStillPicture()
                    } else if (CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED == afState || CaptureResult.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED == afState) {
                        // CONTROL_AE_STATE can be null on some devices
                        val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                        if (aeState == null || aeState == CaptureResult.CONTROL_AE_STATE_CONVERGED) {
                            mState = STATE_PICTURE_TAKEN
                            captureStillPicture()
                        } else {
                            runPrecaptureSequence()
                        }
                    }
                }
                STATE_WAITING_PRECAPTURE -> {
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null ||
                        aeState == CaptureResult.CONTROL_AE_STATE_PRECAPTURE ||
                        aeState == CaptureRequest.CONTROL_AE_STATE_FLASH_REQUIRED
                    ) {
                        mState = STATE_WAITING_NON_PRECAPTURE
                    }
                }
                STATE_WAITING_NON_PRECAPTURE -> {
                    // CONTROL_AE_STATE can be null on some devices
                    val aeState = result.get(CaptureResult.CONTROL_AE_STATE)
                    if (aeState == null || aeState != CaptureResult.CONTROL_AE_STATE_PRECAPTURE) {
                        mState = STATE_PICTURE_TAKEN
                        captureStillPicture()
                    }
                }
            }
        }

        override fun onCaptureProgressed(
            session: CameraCaptureSession,
            request: CaptureRequest,
            partialResult: CaptureResult
        ) {
            process(partialResult)
        }

        override fun onCaptureCompleted(
            session: CameraCaptureSession,
            request: CaptureRequest,
            result: TotalCaptureResult
        ) {
            process(result)
        }

    }

    private fun startBackgroundThread() {
        backgroundThread = HandlerThread("Camera Thread").also { it.start() }
        backgroundHandler = Handler(backgroundThread.looper)
    }

    private fun stopBackgroundThread() {
        try {
            backgroundThread.quitSafely()
            backgroundThread.join()
        } catch (e: Exception) {
            UtilsFunctions.eLog("exception theread", e.toString())
        }
    }

    private val surfaceListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture?, width: Int, height: Int) {

        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {

        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean {
            return true
        }

        override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
            UtilsFunctions.eLog("onSurfaceTextureAvailable", "$width - $height")
            openCamera()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.camera_shoot_layout)
        orientation.put(Surface.ROTATION_0, 90)
        orientation.put(Surface.ROTATION_90, 0)
        orientation.put(Surface.ROTATION_180, 270)
        orientation.put(Surface.ROTATION_270, 180)

        pictureBTN.setOnClickListener { takePicture() }
        galleryBTN.setOnClickListener { galleryPicture() }
        textureView.setOnTouchListener { v, event ->
            val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
            val characteristics = manager.getCameraCharacteristics(cameraId(CameraCharacteristics.LENS_FACING_BACK))

            val maxzoom = characteristics.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM) * 5

            val m = characteristics.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE)
            val action = event.action
            val current_finger_spacing: Float

            if (event.pointerCount > 1) {
                // Multi touch logic
                current_finger_spacing = getFingerSpacing(event)
                if (finger_spacing != 0f) {
                    if (current_finger_spacing > finger_spacing && maxzoom > zoom_level) {
                        zoom_level++
                    } else if (current_finger_spacing < finger_spacing && zoom_level > 1) {
                        zoom_level--
                    }
                    val minW = (m.width() / maxzoom).toInt()
                    val minH = (m.height() / maxzoom).toInt()
                    val difW = m.width() - minW
                    val difH = m.height() - minH
                    var cropW = difW / 100 * zoom_level as Int
                    var cropH = difH / 100 * zoom_level as Int
                    cropW -= cropW and 3
                    cropH -= cropH and 3
                    val zoom = Rect(cropW, cropH, m.width() - cropW, m.height() - cropH)
                    captureRequest.set(CaptureRequest.SCALER_CROP_REGION, zoom)
                }
                finger_spacing = current_finger_spacing
            } else {
                if (action == MotionEvent.ACTION_UP) {
                    //single touch logic
                }
            }

            try {
                captureSession.setRepeatingRequest(captureRequest.build(), mCaptureCallback, null)
            } catch (e: CameraAccessException) {
                e.printStackTrace()
            } catch (ex: NullPointerException) {
                ex.printStackTrace()
            }
            true
        }
        flashBTN.setOnClickListener {
            if (supportsFlash) {
                if (isFlashOn) {
                    flashBTN.setImageResource(R.drawable.ic_flash_off)
                    captureRequest.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                    captureSession.setRepeatingRequest(captureRequest.build(), null, null)
                    isFlashOn = false
                } else {
                    flashBTN.setImageResource(R.drawable.ic_flash_on)
                    captureRequest.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_TORCH)
                    captureSession.setRepeatingRequest(captureRequest.build(), null, null)
                    isFlashOn = true
                }
            } else {
                Toast.makeText(this, "Your phone does not have flash", Toast.LENGTH_LONG).show()
            }
        }
//        Handler().postDelayed(Runnable { takePicture() }, 2000)
    }

    private fun getFingerSpacing(event: MotionEvent): Float {
        val x = event.getX(0) - event.getX(1)
        val y = event.getY(0) - event.getY(1)
        return Math.sqrt((x * x + y * y).toDouble()).toFloat()
    }

    private fun galleryPicture() {
        val intent = Intent(Intent.ACTION_PICK, MediaStore.Images.Media.EXTERNAL_CONTENT_URI)
        startActivityForResult(intent, GALLERY_IMAGE_REQUEST_CODE)
    }

    override fun onResume() {
        super.onResume()
        startBackgroundThread()

        val dir =
            Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES).toString() + "/Marsplay/"
        val directory = File(dir)
        directory.mkdirs()
        val path = dir + "bb.jpg"
        file = File(path)
        try {
            file.createNewFile()
        } catch (e: Exception) {
            UtilsFunctions.eLog("exception", e.toString())
        }


        if (textureView.isAvailable) {
            openCamera()
        } else {
            textureView.surfaceTextureListener = surfaceListener
        }
    }

    override fun onPause() {
        closeCamera()
        stopBackgroundThread()
        super.onPause()
    }

    private var isFlashOn: Boolean = false

    private lateinit var deviceId: String

    @SuppressLint("MissingPermission")
    fun openCamera() {
        deviceId = cameraId(CameraCharacteristics.LENS_FACING_BACK)
        UtilsFunctions.eLog("deveice id", deviceId)
        try {
            cameraManager.openCamera(deviceId, deviceStateCallback, backgroundHandler)
        } catch (e: Exception) {
            UtilsFunctions.eLog("exception camera ", e.toString())
        }
    }

    private fun <T> cameraCharacteristics(cameraId: String, key: CameraCharacteristics.Key<T>): T {
        val characteristics = cameraManager.getCameraCharacteristics(cameraId)
        return when (key) {
            CameraCharacteristics.LENS_FACING -> characteristics.get(key)
            CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP -> characteristics.get(key)
            else -> throw IllegalArgumentException("Illegal argument")
        }
    }

    private fun cameraId(lens: Int): String {
        var deviceId = listOf<String>()
        val cameraListId = cameraManager.cameraIdList
        deviceId = cameraListId.filter { lens == cameraCharacteristics(it, CameraCharacteristics.LENS_FACING) }
        return deviceId[0]
    }

    fun previewSession() {
        imageReader = ImageReader.newInstance(
            width, height,
            ImageFormat.JPEG, /*maxImages*/ 2
        ).apply {
            setOnImageAvailableListener(onImageAvailableListener, backgroundHandler)
        }
        val surfaceTexture = textureView.surfaceTexture
        surfaceTexture.setDefaultBufferSize(width, height)
        val surface = Surface(surfaceTexture)

        captureRequest = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        captureRequest.addTarget(surface)
        val characteristics =
            cameraManager.getCameraCharacteristics(deviceId)
        supportsFlash = characteristics.get(CameraCharacteristics.FLASH_INFO_AVAILABLE) != null
        cameraDevice.createCaptureSession(
            Arrays.asList(surface, imageReader?.surface),
            object : CameraCaptureSession.StateCallback() {
                override fun onConfigureFailed(session: CameraCaptureSession?) {

                }

                override fun onConfigured(session: CameraCaptureSession?) {
                    try {
                        captureSession = session!!
                        captureRequest.set(
                            CaptureRequest.CONTROL_AF_MODE,
                            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
                        )
                        captureSession.setRepeatingRequest(captureRequest.build(), null, null)
                        if (supportsFlash) {
                            captureRequest.set(CaptureRequest.FLASH_MODE, CaptureRequest.FLASH_MODE_OFF)
                            captureSession.setRepeatingRequest(captureRequest.build(), null, null)
                            isFlashOn = false
                        }
                    } catch (e: java.lang.Exception) {
                        UtilsFunctions.eLog("exception capture session", e.toString())
                    }
                }

            },
            null
        )

    }

    private fun closeCamera() {
        if (this::captureSession.isInitialized) {
            captureSession.close()
        }
        if (this::cameraDevice.isInitialized) {
            cameraDevice.close()
        }
    }

    private fun takePicture() {
        try {
            // This is how to tell the camera to lock focus.
            captureRequest.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_START
            )
            // Tell #mCaptureCallback to wait for the lock.
            mState = STATE_WAITING_LOCK
            captureSession.capture(
                captureRequest.build(), mCaptureCallback,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            UtilsFunctions.eLog("exception take picture", e.toString())
        }

    }

    private val onImageAvailableListener = ImageReader.OnImageAvailableListener {
        try {
            backgroundHandler.post {
                kotlin.run {
                    UtilsFunctions.eLog("inside iamge availble", "true")
                    val buffer = it.acquireLatestImage().planes[0].buffer
                    val bytes = ByteArray(buffer.remaining())
                    buffer.get(bytes)
                    var output: FileOutputStream? = null
                    try {
                        output = FileOutputStream(file).apply {
                            write(bytes)
                        }
                    } catch (e: Exception) {
                        UtilsFunctions.eLog("exception io", e.toString())
                    } finally {
                        //                    it.acquireLatestImage().close()
                        output?.let {
                            try {
                                it.close()
                            } catch (e: IOException) {
                                UtilsFunctions.eLog("exception io1", e.toString())
                            }
                        }

                        cropImage(file, false)
//                        finish()
                    }
                }
            }
        } catch (e: Exception) {
            UtilsFunctions.eLog("exception image listener", e.toString())
        }
    }

    private fun cropImage(file: File, gallery: Boolean) {

//        val /*List<ResolveInfo>*/ resInfoList =
//            getPackageManager().queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY);
//        for (resolveInfo in resInfoList) {
//            var packageName = resolveInfo.activityInfo.packageName
//            grantUriPermission(
//                packageName,
//                uri,
//                Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
//            )
//        }

        val fileUri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".fileprovider", file)
        val cropIntent = Intent("com.android.camera.action.CROP")
        cropIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        cropIntent.flags = (Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        cropIntent.setDataAndType(fileUri, "image/*")
        cropIntent.putExtra("crop", "true")
        cropIntent.putExtra("aspectX", 3)
        cropIntent.putExtra("aspectY", 3)
        cropIntent.putExtra("outputX", 512)
        cropIntent.putExtra("outputY", 512)
//        val croppedFile = File(getCacheDir().toString() + "/cache/")
//        if (!croppedFile.exists()) {
//            croppedFile.mkdirs()
//        }
//        val newFile = File(croppedFile, "cropped_image.jpg")
//        newFile.createNewFile()
//        croppedImage = croppedFile.absolutePath
//        val croppedFileUri = FileProvider.getUriForFile(this, BuildConfig.APPLICATION_ID + ".fileprovider", newFile)
//        grantUriPermission(
//            BuildConfig.APPLICATION_ID,
//            croppedFileUri,
//            Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION
//        )
        cropIntent.putExtra(MediaStore.EXTRA_OUTPUT, fileUri)
        cropIntent.putExtra("scaleUpIfNeeded", true)
        cropIntent.putExtra("return-data", false)
        cropIntent.putExtra("crop", true)
        startActivityForResult(cropIntent, PIC_CROP)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        UtilsFunctions.eLog("activity resuult","true")
        if (requestCode == PIC_CROP && resultCode == RESULT_OK) {
            UtilsFunctions.eLog("activity result", "inside")
            try {
                val coverImage = BitmapFactory.decodeStream(contentResolver.openInputStream(data!!.data))
                if (coverImage != null) {
                    UtilsFunctions.eLog("activity result", "inside cover image")
                    val i = Intent(this, UploadActivity::class.java)
                    i.putExtra("image", data.data)
                    var uri = data.data
                    startActivity(i)
                    finish()
//                    uploadRequest(coverImage!!)
                }
            } catch (e: Exception) {
                UtilsFunctions.eLog("exception result", e.toString())
                e.printStackTrace()
            }
        } else if (requestCode == GALLERY_IMAGE_REQUEST_CODE && resultCode == Activity.RESULT_OK) {
            cropGalleryImage(data!!.data)
        }
    }

    private fun cropGalleryImage(uri: Uri?) {
        val cropIntent = Intent("com.android.camera.action.CROP")
        cropIntent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        cropIntent.flags = (Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        cropIntent.setDataAndType(uri, "image/*")
        cropIntent.putExtra("crop", "true")
        cropIntent.putExtra("aspectX", 1)
        cropIntent.putExtra("aspectY", 1)
        croppedImage = "croppedImage.jpg"
        val file = File(Environment.getExternalStorageDirectory(), croppedImage)
        croppedImage = file.absolutePath
        val outputFileUri = Uri.fromFile(file)
        cropIntent.putExtra(MediaStore.EXTRA_OUTPUT, outputFileUri)
        startActivityForResult(cropIntent, PIC_CROP)
    }

    private fun captureStillPicture() {
        try {
            if (null == cameraDevice) {
                return
            }
            // This is the CaptureRequest.Builder that we use to take a picture.
            val captureBuilder = cameraDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
            captureBuilder.addTarget(imageReader!!.getSurface())
            val rotation = windowManager.defaultDisplay.rotation
            // Use the same AE and AF modes as the preview.
            captureBuilder.set(
                CaptureRequest.CONTROL_AF_MODE,
                CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            )
            captureBuilder.set(CaptureRequest.JPEG_ORIENTATION, orientation.get(rotation))

            val captureCallback = object : CameraCaptureSession.CaptureCallback() {

                override fun onCaptureCompleted(
                    session: CameraCaptureSession,
                    request: CaptureRequest,
                    result: TotalCaptureResult
                ) {
                    UtilsFunctions.eLog("Saved:", file.toString())
                    UtilsFunctions.eLog("picture", "Picture clicked and saved")
                    val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
                    val contentUri = Uri.fromFile(file)
                    mediaScanIntent.setData(contentUri)
                    this@CameraShootActivity.sendBroadcast(mediaScanIntent)
                    unlockFocus()
                }
            }

            captureSession.stopRepeating()
            captureSession.abortCaptures()
            captureSession.capture(captureBuilder.build(), captureCallback, null)
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

    }

    private fun unlockFocus() {
        try {
            // Reset the auto-focus trigger
            captureRequest.set(
                CaptureRequest.CONTROL_AF_TRIGGER,
                CameraMetadata.CONTROL_AF_TRIGGER_CANCEL
            )
            captureSession.capture(
                captureRequest.build(), mCaptureCallback,
                backgroundHandler
            )
            // After this, the camera will go back to the normal state of preview.
            mState = STATE_PREVIEW
            captureSession.setRepeatingRequest(
                captureRequest.build(), mCaptureCallback,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

    }

    private fun runPrecaptureSequence() {
        try {
            // This is how to tell the camera to trigger.
            captureRequest.set(
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START
            )
            // Tell CaptureCallback to wait for the precapture sequence to be set.
            mState = STATE_WAITING_PRECAPTURE
            captureSession.capture(
                captureRequest.build(), mCaptureCallback,
                backgroundHandler
            )
        } catch (e: CameraAccessException) {
            e.printStackTrace()
        }

    }

}
