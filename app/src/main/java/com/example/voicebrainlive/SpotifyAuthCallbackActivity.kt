package com.example.voicebrainlive

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.FormBody
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Receives voicebrainlive://callback after Spotify OAuth.
 * Extracts code, exchanges for tokens, saves, finishes.
 */
class SpotifyAuthCallbackActivity : AppCompatActivity() {

    private val httpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleCallback(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleCallback(intent)
    }

    private fun handleCallback(intent: Intent?) {
        val data = intent?.data ?: run {
            finishWithError("No callback data")
            return
        }
        if (data.scheme != "voicebrainlive" || data.host != "callback") {
            finishWithError("Invalid callback")
            return
        }
        val code = data.getQueryParameter("code")
        if (code.isNullOrBlank()) {
            val error = data.getQueryParameter("error") ?: "No code"
            Log.w("SpotifyAuth", "OAuth error: $error")
            finishWithError(error)
            return
        }
        lifecycleScope.launch {
            val ok = withContext(Dispatchers.IO) {
                exchangeCodeForToken(code)
            }
            if (ok) {
                Toast.makeText(this@SpotifyAuthCallbackActivity, "Spotify ချိတ်ဆက်ပြီးပါပြီ", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(this@SpotifyAuthCallbackActivity, "Spotify ချိတ်ဆက်မရပါ - ပြန်စမ်းပါ", Toast.LENGTH_LONG).show()
            }
            finish()
        }
    }

    private fun exchangeCodeForToken(code: String): Boolean {
        val codeVerifier = SettingsActivity.getSpotifyPkceVerifier(this)
        if (codeVerifier.isNullOrBlank()) {
            Log.e("SpotifyAuth", "No PKCE verifier - was auth started from this app?")
            return false
        }
        if (!SettingsActivity.clearSpotifyPkceVerifier(this)) return false
        val clientId = BuildConfig.SPOTIFY_CLIENT_ID
        val redirectUri = BuildConfig.SPOTIFY_REDIRECT_URI
        val body = FormBody.Builder()
            .add("grant_type", "authorization_code")
            .add("code", code)
            .add("redirect_uri", redirectUri)
            .add("client_id", clientId)
            .add("code_verifier", codeVerifier)
            .build()
        val req = Request.Builder()
            .url("https://accounts.spotify.com/api/token")
            .post(body)
            .addHeader("Content-Type", "application/x-www-form-urlencoded")
            .build()
        return try {
            val resp = httpClient.newCall(req).execute()
            if (!resp.isSuccessful) {
                Log.e("SpotifyAuth", "Token exchange failed: ${resp.code} ${resp.body?.string()}")
                return false
            }
            val json = JSONObject(resp.body?.string() ?: return false)
            val accessToken = json.optString("access_token").takeIf { it.isNotBlank() } ?: return false
            val refreshToken = json.optString("refresh_token").takeIf { it.isNotBlank() }
            SettingsActivity.saveSpotifyTokens(this, accessToken, refreshToken)
        } catch (e: Exception) {
            Log.e("SpotifyAuth", "Token exchange: ${e.message}", e)
            false
        }
    }

    private fun finishWithError(msg: String) {
        Toast.makeText(this, "Spotify: $msg", Toast.LENGTH_SHORT).show()
        finish()
    }
}
