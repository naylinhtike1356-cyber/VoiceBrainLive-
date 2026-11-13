package com.example.voicebrainlive.updates

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Toast
import androidx.core.content.FileProvider
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.lifecycleScope
import com.example.voicebrainlive.BuildConfig
import com.example.voicebrainlive.ThemeUtils
import com.example.voicebrainlive.databinding.ActivityAppUpdateBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class AppUpdateActivity : FragmentActivity() {
    private lateinit var binding: ActivityAppUpdateBinding
    private var currentProvider: ReleaseProvider = UnconfiguredReleaseProvider
    private var availableRelease: ReleaseManifest? = null
    private var pendingInstallApk: File? = null
    private var isDownloading = false

    internal var providerFactory: () -> ReleaseProvider = {
        ReleaseSource.provider(packageName)
    }

    companion object {
        private const val REQUEST_CODE_INSTALL_PERMISSION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityAppUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)

        binding.btnBack.setOnClickListener { finish() }

        binding.tvInstalledVersion.text = "Version: ${BuildConfig.VERSION_NAME}\nBuild: ${BuildConfig.VERSION_CODE}\nBuild type: ${BuildConfig.BUILD_TYPE}"
        binding.tvDistributionChannel.text = when {
            BuildConfig.BUILD_TYPE == "agentPilot" -> "Distribution: private agent pilot; not a published release"
            BuildConfig.DEBUG -> "Distribution: test / debug build"
            else -> "Distribution: official HTTPS release"
        }

        binding.tvUpdateStatus.text = "တရားဝင် HTTPS release မှ တိုက်ရိုက် update ရယူနိုင်ပါသည်။ Token မလိုပါ။"

        if (!providerFactory().configured) showStatus(UpdateStatus.NotConfigured)

        binding.btnCheckUpdate.setOnClickListener {
            performUpdateCheck()
        }

        binding.btnDownloadInstall.setOnClickListener {
            performDownloadAndInstall()
        }

        binding.btnRemindLater.setOnClickListener {
            val rel = availableRelease
            if (rel != null) {
                getSharedPreferences("app_update_prefs", Context.MODE_PRIVATE)
                    .edit()
                    .putLong("dismissed_update_code", rel.androidVersionCode)
                    .apply()
            }
            finish()
        }
    }

    override fun onResume() {
        super.onResume()
        // If user just returned from unknown sources settings with granted permission
        if (pendingInstallApk != null && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            if (packageManager.canRequestPackageInstalls()) {
                val file = pendingInstallApk
                pendingInstallApk = null
                file?.let { launchPackageInstaller(it) }
            }
        }

        // If app has already been updated to this version or newer
        val rel = availableRelease
        if (rel != null && BuildConfig.VERSION_CODE >= rel.androidVersionCode) {
            binding.layoutNewUpdate.visibility = View.GONE
            binding.tvUpdateStatus.text = "သင့်အက်ပ်သည် နောက်ဆုံးဗားရှင်း ဖြစ်ပါသည် (No newer version is available)."
        }
    }

    private fun getActiveProvider(): ReleaseProvider {
        return providerFactory()
    }

    private fun performUpdateCheck() {
        if (isDownloading) return
        pendingInstallApk = null
        availableRelease = null
        binding.btnCheckUpdate.isEnabled = false
        binding.btnDownloadInstall.isEnabled = false
        binding.progressUpdate.visibility = View.VISIBLE
        binding.layoutNewUpdate.visibility = View.GONE

        lifecycleScope.launch {
            try {
                currentProvider = getActiveProvider()
                val checker = UpdateChecker(packageName, BuildConfig.VERSION_CODE.toLong(), currentProvider)
                val status = checker.check()
                showStatus(status)
            } finally {
                binding.progressUpdate.visibility = View.GONE
                binding.btnCheckUpdate.isEnabled = true
            }
        }
    }

    private fun showStatus(status: UpdateStatus) {
        binding.tvReleaseNotes.text = ""
        binding.tvReleaseNotes.visibility = View.GONE
        binding.layoutNewUpdate.visibility = View.GONE

        when (status) {
            UpdateStatus.NotConfigured -> {
                binding.tvUpdateStatus.text = "No trusted update source configured. Live updates are NOT CONFIGURED."
                binding.btnDownloadInstall.isEnabled = false
                binding.btnRemindLater.visibility = View.GONE
            }
            UpdateStatus.Current -> {
                binding.tvUpdateStatus.text = "သင့်အက်ပ်သည် နောက်ဆုံးဗားရှင်း ဖြစ်ပါသည် (No newer version is available)."
                binding.btnDownloadInstall.isEnabled = false
                binding.btnRemindLater.visibility = View.GONE
            }
            UpdateStatus.Failed -> {
                binding.tvUpdateStatus.text = "အပ်ဒိတ် စစ်ဆေး၍မရပါ။ အင်တာနက် သို့မဟုတ် update feed ကို စစ်ဆေးပြီး ခဏကြာမှ ပြန်စမ်းပါ။ Token မလိုပါ။"
                binding.btnDownloadInstall.isEnabled = false
                binding.btnRemindLater.visibility = View.GONE
            }
            is UpdateStatus.Available -> {
                val rel = status.release
                availableRelease = rel
                binding.layoutNewUpdate.visibility = View.VISIBLE
                val sizeMb = rel.apkBytes / (1024.0 * 1024.0)
                binding.tvNewVersionInfo.text = "လက်ရှိ ဗားရှင်း: v${BuildConfig.VERSION_NAME} (Build ${BuildConfig.VERSION_CODE})\n" +
                    "ဗားရှင်းအသစ်: v${rel.androidVersionName} (Build ${rel.androidVersionCode})\n" +
                    "ဒေါင်းလုဒ် အရွယ်အစား: %.1f MB".format(sizeMb)

                if (rel.releaseNotes.isNotBlank()) {
                    binding.tvReleaseNotes.text = rel.releaseNotes
                    binding.tvReleaseNotes.visibility = View.VISIBLE
                    binding.tvReleaseNotesHeader.visibility = View.VISIBLE
                } else {
                    binding.tvReleaseNotes.visibility = View.GONE
                    binding.tvReleaseNotesHeader.visibility = View.GONE
                }

                val updatesDir = File(cacheDir, "updates")
                val targetApk = File(updatesDir, rel.apkName)
                if (targetApk.exists()) {
                    try {
                        ApkUpdateVerifier.verify(this, targetApk, rel)
                        binding.tvDownloadProgress.text = "APK ဖိုင်ကို စစ်ဆေးပြီးပါပြီ။ Update တင်နိုင်ပါပြီ။"
                        binding.btnDownloadInstall.text = "Update တင်မည် (Install Now)"
                        pendingInstallApk = targetApk
                    } catch (_: Exception) {
                        targetApk.delete()
                        binding.btnDownloadInstall.text = "အသစ် ရယူတပ်ဆင်မည် (Download Update)"
                    }
                } else {
                    binding.btnDownloadInstall.text = "အသစ် ရယူတပ်ဆင်မည် (Download Update)"
                }

                binding.tvUpdateStatus.text = "Version ${rel.androidVersionName} (${rel.androidVersionCode}) is available. Downloads are ready."
                binding.btnDownloadInstall.isEnabled = true
                binding.btnRemindLater.visibility = View.VISIBLE
            }
        }
    }

    private fun performDownloadAndInstall() {
        val rel = availableRelease
        if (rel == null) {
            Toast.makeText(this, "ဒေါင်းလုဒ်ဆွဲရန် အချက်အလက် မပြည့်စုံပါ", Toast.LENGTH_SHORT).show()
            return
        }

        val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
        val targetApk = File(updatesDir, rel.apkName)

        // If already downloaded and verified
        if (targetApk.exists() && pendingInstallApk?.absolutePath == targetApk.absolutePath) {
            promptInstall(targetApk)
            return
        }

        if (isDownloading) return
        isDownloading = true

        binding.btnDownloadInstall.isEnabled = false
        binding.btnCheckUpdate.isEnabled = false
        binding.btnRemindLater.isEnabled = false
        binding.layoutDownloadProgress.visibility = View.VISIBLE
        binding.progressDownload.progress = 0
        binding.tvDownloadProgress.text = "ဒေါင်းလုဒ် စတင်နေပါသည်..."

        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val activeProvider = currentProvider
                    when (val provider = (activeProvider as? FallbackReleaseProvider)?.selected ?: activeProvider) {
                        is HttpReleaseProvider -> {
                            provider.download(rel, targetApk) { downloaded, total ->
                                updateDownloadProgress(downloaded, total)
                            }
                        }
                        is GitHubReleaseProvider -> {
                            val availableUpdate = provider.lastAvailableUpdate
                                ?: throw IllegalStateException("GitHub update manifest missing")
                            provider.gitHubUpdates.download(availableUpdate, targetApk) { downloaded, total ->
                                updateDownloadProgress(downloaded, total)
                            }
                        }
                        else -> throw IllegalStateException("Configured provider is not downloadable")
                    }
                }

                // Verify the downloaded APK
                binding.tvDownloadProgress.text = "APK အချက်အလက်များ စစ်ဆေးနေပါသည်..."
                withContext(Dispatchers.IO) {
                    ApkUpdateVerifier.verify(this@AppUpdateActivity, targetApk, rel)
                }

                binding.tvDownloadProgress.text = "APK ဖိုင်ကို စစ်ဆေးပြီးပါပြီ။ Update တင်နိုင်ပါပြီ။"
                binding.btnDownloadInstall.text = "Update တင်မည် (Install Now)"
                binding.btnDownloadInstall.isEnabled = true
                binding.btnRemindLater.isEnabled = true
                pendingInstallApk = targetApk
                promptInstall(targetApk)

            } catch (e: Exception) {
                targetApk.delete()
                binding.tvDownloadProgress.text = "ဒေါင်းလုဒ် သို့မဟုတ် စစ်ဆေးမှု မအောင်မြင်ပါ: ${e.message}"
                binding.btnDownloadInstall.text = "အသစ် ပြန်လည်ရယူမည် (Retry)"
                binding.btnDownloadInstall.isEnabled = true
                binding.btnRemindLater.isEnabled = true
            } finally {
                isDownloading = false
                binding.btnCheckUpdate.isEnabled = true
            }
        }
    }

    private fun updateDownloadProgress(downloaded: Long, total: Long) {
        val percent = if (total > 0) ((downloaded * 100) / total).toInt() else 0
        runOnUiThread {
            binding.progressDownload.progress = percent
            binding.tvDownloadProgress.text = "ဒေါင်းလုဒ်ဆွဲနေသည်... %.1f MB / %.1f MB (%d%%)".format(
                downloaded / (1024.0 * 1024.0),
                total / (1024.0 * 1024.0),
                percent
            )
        }
    }

    private fun promptInstall(apkFile: File) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && !packageManager.canRequestPackageInstalls()) {
            pendingInstallApk = apkFile
            Toast.makeText(this, "အက်ပ် Update တင်နိုင်ရန် 'Allow from this source' ခွင့်ပြုပေးပါ", Toast.LENGTH_LONG).show()
            val intent = Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES).apply {
                data = Uri.parse("package:$packageName")
            }
            startActivityForResult(intent, REQUEST_CODE_INSTALL_PERMISSION)
        } else {
            launchPackageInstaller(apkFile)
        }
    }

    private fun launchPackageInstaller(apkFile: File) {
        try {
            val apkUri = FileProvider.getUriForFile(this, "${packageName}.fileprovider", apkFile)
            val installIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(apkUri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(installIntent)
        } catch (e: Exception) {
            Toast.makeText(this, "တပ်ဆင်မှု မစတင်နိုင်ပါ: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_CODE_INSTALL_PERMISSION) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.canRequestPackageInstalls()) {
                pendingInstallApk?.let { launchPackageInstaller(it) }
            } else {
                Toast.makeText(this, "ခွင့်ပြုချက် မရရှိပါက Update မတင်နိုင်ပါ", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
