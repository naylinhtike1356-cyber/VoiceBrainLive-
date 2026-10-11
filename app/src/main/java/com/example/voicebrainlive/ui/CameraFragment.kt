package com.example.voicebrainlive.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.util.Base64
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.*
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import com.example.voicebrainlive.MainActivityViewModel
import com.example.voicebrainlive.databinding.FragmentCameraBinding
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraFragment : Fragment() {

    private var _binding: FragmentCameraBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MainActivityViewModel by activityViewModels()

    private var shareDialog: android.app.AlertDialog? = null
    private var captureGeneration = 0L
    private var imageCapture: ImageCapture? = null
    private lateinit var cameraExecutor: ExecutorService
    private var cameraProvider: ProcessCameraProvider? = null

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            startCamera()
        } else {
            Toast.makeText(context, "Camera permission required for vision", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentCameraBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        cameraExecutor = Executors.newSingleThreadExecutor()
        
        binding.btnCapture.setOnClickListener {
            captureToGemini()
        }

        binding.btnCloseCamera.setOnClickListener {
            stopCamera()
            // Typically would navigate back or close fragment
        }

        if (allPermissionsGranted()) {
            startCamera()
        } else {
            requestPermissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    private fun allPermissionsGranted() = ContextCompat.checkSelfPermission(
        requireContext(), Manifest.permission.CAMERA
    ) == PackageManager.PERMISSION_GRANTED

    private fun startCamera() {
        val cameraProviderFuture = ProcessCameraProvider.getInstance(requireContext())

        cameraProviderFuture.addListener({
            try {
                cameraProvider = cameraProviderFuture.get()

                val preview = Preview.Builder().build().also {
                    it.setSurfaceProvider(binding.cameraPreview.surfaceProvider)
                }

                imageCapture = ImageCapture.Builder()
                    .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                    .build()

                val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

                cameraProvider?.unbindAll()
                cameraProvider?.bindToLifecycle(viewLifecycleOwner, cameraSelector, preview, imageCapture)
            } catch (exc: Exception) {
                Log.e("CameraFragment", "Use case binding failed", exc)
                binding.tvCameraStatus.text = "Camera Error"
            }

        }, ContextCompat.getMainExecutor(requireContext()))
    }

    private fun stopCamera() {
        cameraProvider?.unbindAll()
    }

    private fun captureToGemini() {
        if (!isResumed) return
        val generation = ++captureGeneration
        val client = viewModel.geminiClient
        val connection = client.imageSessionToken() ?: return
        val sessionExecutor = viewModel.phoneExecutor
        val imageCapture = imageCapture ?: return

        imageCapture.takePicture(cameraExecutor, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureSuccess(image: ImageProxy) {
                val bitmap = imageProxyToBitmap(image)
                image.close()
                
                if (bitmap != null) {
                    val base64 = bitmapToBase64(bitmap)
                    activity?.runOnUiThread {
                        if (!isResumed || generation != captureGeneration || (viewModel.geminiClient !== client || viewModel.phoneExecutor !== sessionExecutor)) return@runOnUiThread
                        val consent = com.example.voicebrainlive.commands.ImageShareConsent(base64, client, {
                            _binding != null && isResumed && generation == captureGeneration && viewModel.geminiClient === client &&
                                viewModel.phoneExecutor === sessionExecutor && sessionExecutor.isAuthorizationSessionOpen() && client.isImageSessionCurrent(connection) &&
                                ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED &&
                                !(requireContext().getSystemService(android.content.Context.KEYGUARD_SERVICE) as android.app.KeyguardManager).isDeviceLocked
                        })
                        val jpeg = Base64.decode(base64, Base64.NO_WRAP)
                        val reviewed = android.graphics.BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size)
                        val preview = android.widget.ImageView(requireContext()).apply {
                            setImageBitmap(reviewed)
                            adjustViewBounds = true
                            filterTouchesWhenObscured = true
                        }
                        shareDialog?.dismiss()
                        shareDialog = android.app.AlertDialog.Builder(requireContext())
                            .setTitle("Share this exact image with Gemini AI?")
                            .setMessage("This image will leave your device and be sent to the current Gemini session.")
                            .setView(preview)
                            .setPositiveButton("Share image") { _, _ ->
                                val tap = com.example.voicebrainlive.commands.ApprovalUiLease.tap(this)
                                client.sendReviewedImage(consent, tap)
                                ++captureGeneration // no recapture or retry after this reviewed frame
                            }
                            .setOnCancelListener { consent.cancel(); ++captureGeneration }
                            .setOnDismissListener { consent.cancel() }
                            .setNegativeButton("Cancel") { _, _ -> ++captureGeneration }.create().also {
                                it.window?.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                                it.show()
                                it.getButton(android.app.AlertDialog.BUTTON_POSITIVE).filterTouchesWhenObscured = true
                            }
                    }
                }
            }

            override fun onError(exc: ImageCaptureException) {
                Log.e("CameraFragment", "Photo capture failed: ${exc.message}", exc)
                activity?.runOnUiThread {
                    Toast.makeText(context, "Capture Failed", Toast.LENGTH_SHORT).show()
                }
            }
        })
    }

    private fun imageProxyToBitmap(image: ImageProxy): Bitmap? {
        val planeProxy = image.planes[0]
        val buffer = planeProxy.buffer
        val bytes = ByteArray(buffer.remaining())
        buffer.get(bytes)
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        
        val rotationDegrees = image.imageInfo.rotationDegrees
        return if (rotationDegrees != 0) {
            val matrix = android.graphics.Matrix()
            matrix.postRotate(rotationDegrees.toFloat())
            Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, true)
        } else {
            bitmap
        }
    }

    private fun bitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 70, outputStream)
        val byteArray = outputStream.toByteArray()
        return Base64.encodeToString(byteArray, Base64.NO_WRAP)
    }

    override fun onResume() {
        super.onResume()
        com.example.voicebrainlive.commands.ApprovalUiLease.resume(this)
    }

    override fun onPause() {
        com.example.voicebrainlive.commands.ApprovalUiLease.pause(this)
        ++captureGeneration
        shareDialog?.dismiss()
        shareDialog = null
        super.onPause()
    }

    override fun onDestroyView() {
        ++captureGeneration
        com.example.voicebrainlive.commands.ApprovalUiLease.pause(this)
        shareDialog?.dismiss()
        shareDialog = null
        super.onDestroyView()
        cameraExecutor.shutdown()
        _binding = null
    }
}
