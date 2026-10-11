package com.example.voicebrainlive

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Environment
import android.util.Log
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.TimeUnit

/**
 * AI Image Generation - Generate images using AI
 * v2.0 Feature
 */
/**
 * AI Image Generation - Imagen (Google) and DALL-E (OpenAI).
 * @param geminiApiKey For Imagen (Google AI)
 * @param openAIApiKey For DALL-E (OpenAI) - optional, falls back to browser if missing
 */
class ImageGenerator(
    private val context: Context,
    private val geminiApiKey: String,
    private val openAIApiKey: String? = null
) {
    private val apiKey: String get() = geminiApiKey // For Imagen
    
    companion object {
        const val TAG = "ImageGenerator"
        
        // Supported models (stable-diffusion not yet implemented)
        val MODELS = listOf(
            ImageModel("imagen-4-fast", "Google Imagen 4 Fast", "Fast Google AI image generator for higher-volume use"),
            ImageModel("imagen-4", "Google Imagen 4", "Latest stable Google AI image generator"),
            ImageModel("imagen-4-ultra", "Google Imagen 4 Ultra", "Highest quality Imagen 4 generator"),
            ImageModel("dalle-3", "DALL-E 3", "OpenAI's DALL-E 3")
        )
    }
    
    private val client = OkHttpClient.Builder()
        .connectTimeout(120, TimeUnit.SECONDS)
        .readTimeout(120, TimeUnit.SECONDS)
        .build()
    
    /**
     * Generate image from text prompt
     */
    suspend fun generateImage(
        prompt: String,
        model: String = "imagen-4-fast",
        size: String = "1024x1024"
    ): ImageGenerationResult = withContext(Dispatchers.IO) {
        try {
            when (model) {
                "imagen-4-fast", "imagen-3" -> generateWithImagen(prompt, size, "imagen-4.0-fast-generate-001")
                "imagen-4", "imagen-4-standard" -> generateWithImagen(prompt, size, "imagen-4.0-generate-001")
                "imagen-4-ultra" -> generateWithImagen(prompt, size, "imagen-4.0-ultra-generate-001")
                "dalle-3" -> generateWithDalle(prompt, size)
                else -> ImageGenerationResult(false, "Unsupported model: $model")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Generation error: ${e.message}")
            ImageGenerationResult(false, "Error: ${e.message}")
        }
    }
    
    /**
     * Generate with Google Imagen
     */
    private suspend fun generateWithImagen(
        prompt: String,
        size: String,
        imagenModel: String
    ): ImageGenerationResult {
        val url = "https://generativelanguage.googleapis.com/v1beta/models/$imagenModel:predict?key=$apiKey"
        
        val jsonBody = JSONObject().apply {
            put("instances", org.json.JSONArray().apply {
                put(JSONObject().apply { put("prompt", prompt) })
            })
            put("parameters", JSONObject().apply {
                put("sampleCount", 1)
                put("aspectRatio", when (size) {
                    "1024x1024" -> "1:1"
                    "1024x1792" -> "9:16"
                    "1792x1024" -> "16:9"
                    else -> "1:1"
                })
                put("personGeneration", "allow_adult")
                if (imagenModel != "imagen-4.0-fast-generate-001") {
                    put("imageSize", "1K")
                }
            })
        }
        
        return try {
            val request = Request.Builder()
                .url(url)
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    ImageGenerationResult(false, "No response from API")
                } else {
                    val json = JSONObject(responseBody)
                    val predictions = json.optJSONArray("predictions")
                    if (predictions != null && predictions.length() > 0) {
                        val prediction = predictions.getJSONObject(0)
                        val imageBase64 = prediction.optString("bytesBase64Encoded")
                            .takeIf { it.isNotBlank() }
                            ?: prediction.optJSONObject("image")?.optString("base64")
                        if (!imageBase64.isNullOrBlank()) {
                            saveImage(imageBase64)
                        } else {
                            val reason = prediction.optString("raiFilteredReason", "No image generated")
                            ImageGenerationResult(false, reason)
                        }
                    } else {
                        ImageGenerationResult(false, "No image generated")
                    }
                }
            } else {
                ImageGenerationResult(false, "API error: ${response.code}")
            }
        } catch (e: Exception) {
            // Fallback: open image generation website
            openImageGenerator(prompt)
            ImageGenerationResult(true, "Opened image generator. Check browser for result.")
        }
    }
    
    /**
     * Generate with DALL-E (requires OpenAI API key)
     */
    private suspend fun generateWithDalle(prompt: String, size: String): ImageGenerationResult {
        val key = openAIApiKey?.takeIf { it.isNotBlank() && it != "YOUR_API_KEY" }
            ?: return ImageGenerationResult(false, "OpenAI API key required. Add OPENAI_API_KEY in gradle.properties or Settings.")
        val url = "https://api.openai.com/v1/images/generations"
        val jsonBody = JSONObject().apply {
            put("prompt", prompt)
            put("n", 1)
            put("size", size)
            put("model", "dall-e-3")
        }
        
        return try {
            val request = Request.Builder()
                .url(url)
                .addHeader("Authorization", "Bearer $key")
                .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val responseBody = response.body?.string()
                if (responseBody.isNullOrBlank()) {
                    return ImageGenerationResult(false, "No response from DALL-E API")
                }
                val json = JSONObject(responseBody)
                
                val data = json.getJSONArray("data")
                if (data.length() > 0) {
                    val imageUrl = data.getJSONObject(0).getString("url")
                    
                    // Download and save
                    downloadAndSaveImage(imageUrl)
                } else {
                    ImageGenerationResult(false, "No image generated")
                }
            } else {
                ImageGenerationResult(false, "API error: ${response.code}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "DALL-E error: ${e.message}")
            openImageGenerator(prompt)
            ImageGenerationResult(true, "Opened image generator. Check browser for result.")
        }
    }
    
    /**
     * Save base64 image
     */
    private fun saveImage(base64String: String): ImageGenerationResult {
        return try {
            val imageBytes = android.util.Base64.decode(base64String, android.util.Base64.DEFAULT)
            val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
            
            saveBitmap(bitmap)
        } catch (e: Exception) {
            Log.e(TAG, "Save error: ${e.message}")
            ImageGenerationResult(false, "Could not save image: ${e.message}")
        }
    }
    
    /**
     * Download and save image from URL
     */
    private fun downloadAndSaveImage(url: String): ImageGenerationResult {
        return try {
            val request = Request.Builder()
                .url(url)
                .build()
            
            val response = client.newCall(request).execute()
            
            if (response.isSuccessful) {
                val imageBytes = response.body?.bytes() ?: return ImageGenerationResult(false, "Empty response")
                val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
                
                saveBitmap(bitmap)
            } else {
                ImageGenerationResult(false, "Download failed")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Download error: ${e.message}")
            ImageGenerationResult(false, "Could not download image: ${e.message}")
        }
    }
    
    /**
     * Save bitmap to file
     */
    private fun saveBitmap(bitmap: Bitmap): ImageGenerationResult {
        return try {
            val filename = "AI_Image_${System.currentTimeMillis()}.png"
            val file = File(context.getExternalFilesDir(Environment.DIRECTORY_PICTURES), filename)
            
            FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            
            // Notify gallery
            val mediaScanIntent = Intent(Intent.ACTION_MEDIA_SCANNER_SCAN_FILE)
            mediaScanIntent.data = getContentUri(file)
            context.sendBroadcast(mediaScanIntent)
            
            Log.d(TAG, "Image saved: ${file.absolutePath}")
            
            // Open image viewer
            openImage(file)
            
            ImageGenerationResult(true, "Image generated and saved: $filename")
        } catch (e: Exception) {
            Log.e(TAG, "Save bitmap error: ${e.message}")
            ImageGenerationResult(false, "Could not save image: ${e.message}")
        }
    }
    
    /**
     * Open image in viewer
     */
    private fun openImage(file: File) {
        try {
            val imageUri = getContentUri(file)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(imageUri, "image/*")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Open image error: ${e.message}")
        }
    }

    private fun getContentUri(file: File): Uri {
        return FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
    }
    
    /**
     * Open web-based image generator as fallback
     */
    private fun openImageGenerator(prompt: String) {
        try {
            // Open Bing Image Creator or similar
            val encodedPrompt = Uri.encode(prompt)
            val url = "https://www.bing.com/images/create?q=$encodedPrompt"
            
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e(TAG, "Open generator error: ${e.message}")
        }
    }
    
    /**
     * Get available models
     */
    fun getModels(): List<ImageModel> = MODELS
    
    /**
     * Handle voice commands
     */
    fun handleVoiceCommand(command: String, target: String?, value: String?): String {
        return when (command.lowercase()) {
            "generate_image", "create_image", "draw" -> {
                if (!target.isNullOrBlank()) {
                    val model = value ?: "imagen-4-fast"
                    "Generating image: $target. This may take a moment..."
                } else {
                    "What would you like me to draw?"
                }
            }
            "open_image_generator" -> {
                openImageGenerator(target ?: "")
                "Opening image generator"
            }
            "list_models", "image_models" -> {
                "Available image models: ${MODELS.joinToString(", ") { it.name }}"
            }
            else -> "Unknown image command"
        }
    }
    
    /**
     * Generate image from voice command
     */
    suspend fun generateFromVoice(prompt: String): ImageGenerationResult {
        return generateImage(prompt)
    }
}

data class ImageModel(
    val id: String,
    val name: String,
    val description: String
)

data class ImageGenerationResult(
    val success: Boolean,
    val message: String,
    val filePath: String? = null
)
