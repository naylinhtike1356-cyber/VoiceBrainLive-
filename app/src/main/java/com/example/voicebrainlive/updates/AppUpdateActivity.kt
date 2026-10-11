package com.example.voicebrainlive.updates

import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import com.example.voicebrainlive.BuildConfig
import com.example.voicebrainlive.ThemeUtils
import com.example.voicebrainlive.databinding.ActivityAppUpdateBinding
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AppUpdateActivity : FragmentActivity() {
    private lateinit var binding: ActivityAppUpdateBinding
    private var available: AvailableUpdate? = null
    private var verifiedApk: File? = null
    private var checkedToken: String? = null
    private val prefs by lazy {
        runCatching {
            val key = MasterKey.Builder(this).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
            EncryptedSharedPreferences.create(this, "github_updates_secure", key,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM)
        }.getOrNull()
    }
    private val installPermission = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        if (packageManager.canRequestPackageInstalls()) installApk()
        else binding.tvUpdateStatus.text = "Installation permission was not granted."
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityAppUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        binding.btnBack.setOnClickListener { finish() }
        binding.tvInstalledVersion.text = "Installed: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        binding.etGithubToken.setText(prefs?.getString(TOKEN_KEY, ""))
        binding.btnReleasePage.setOnClickListener {
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/${BuildConfig.UPDATE_REPOSITORY}/releases/latest")))
            } catch (_: android.content.ActivityNotFoundException) {
                binding.tvUpdateStatus.text = "No browser is available."
            }
        }
        binding.btnCheckUpdate.setOnClickListener { checkUpdate() }
        binding.btnInstallUpdate.setOnClickListener { downloadUpdate() }
    }

    private fun busy(value: Boolean) {
        binding.progressUpdate.visibility = if (value) View.VISIBLE else View.GONE
        binding.btnCheckUpdate.isEnabled = !value
        binding.etGithubToken.isEnabled = !value
        binding.btnInstallUpdate.isEnabled = !value && available != null
    }

    private fun checkUpdate() {
        val token = binding.etGithubToken.text.toString().trim()
        available = null
        verifiedApk = null
        checkedToken = null
        binding.btnInstallUpdate.visibility = View.GONE
        if (token.isBlank()) {
            prefs?.edit()?.remove(TOKEN_KEY)?.apply()
            binding.tvUpdateStatus.text = "Enter a GitHub token with Contents read access, or open the release page and sign in."
            return
        }
        prefs?.edit()?.putString(TOKEN_KEY, token)?.apply()
        busy(true)
        binding.tvUpdateStatus.text = "Checking for updates..."
        lifecycleScope.launch {
            try {
                val update = withContext(Dispatchers.IO) { GitHubUpdates(token).latest(packageName) }
                if (update.manifest.isNewerThan(BuildConfig.VERSION_CODE.toLong())) {
                    available = update
                    checkedToken = token
                    binding.tvUpdateStatus.text = "Version ${update.manifest.versionName} is available."
                    binding.btnInstallUpdate.visibility = View.VISIBLE
                } else {
                    binding.tvUpdateStatus.text = "You have the latest version (${BuildConfig.VERSION_NAME})."
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                binding.tvUpdateStatus.text = e.message ?: "Could not check for updates."
            } finally {
                busy(false)
            }
        }
    }

    private fun downloadUpdate() {
        val update = available ?: return
        val token = checkedToken ?: return
        busy(true)
        binding.tvUpdateStatus.text = "Downloading and verifying version ${update.manifest.versionName}..."
        lifecycleScope.launch {
            try {
                val apk = withContext(Dispatchers.IO) {
                    GitHubUpdates(token).download(update, File(cacheDir, "VoiceBrainLive-update.apk"))
                        .also { verifyApk(it, update.manifest) }
                }
                verifiedApk = apk
                binding.tvUpdateStatus.text = "Update verified. Confirm installation in Android."
                if (packageManager.canRequestPackageInstalls()) installApk()
                else installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                verifiedApk = null
                binding.tvUpdateStatus.text = e.message ?: "Could not install the update."
            } finally {
                busy(false)
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun verifyApk(file: File, manifest: ReleaseManifest) {
        try {
            val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
            val archive = packageManager.getPackageArchiveInfo(file.path, flags) ?: error("APK is not valid")
            require(archive.packageName == packageName) { "APK belongs to a different app" }
            val version = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
            require(version == manifest.versionCode && version > BuildConfig.VERSION_CODE) { "APK version does not match the update" }
            val installed = packageManager.getPackageInfo(packageName, flags)
            fun signers(info: PackageInfo): Set<String> = (if (Build.VERSION.SDK_INT >= 28)
                info.signingInfo?.apkContentsSigners else info.signatures)
                ?.map { it.toCharsString() }?.toSet().orEmpty()
            require(signers(installed).isNotEmpty() && signers(installed) == signers(archive)) {
                "This APK uses a different signing key. It cannot update this installation."
            }
        } catch (e: Exception) {
            file.delete()
            throw e
        }
    }

    private fun installApk() {
        val apk = verifiedApk ?: return
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", apk)
            startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        } catch (_: android.content.ActivityNotFoundException) {
            binding.tvUpdateStatus.text = "No Android package installer is available."
        }
    }

    companion object { private const val TOKEN_KEY = "github_update_token" }
}
