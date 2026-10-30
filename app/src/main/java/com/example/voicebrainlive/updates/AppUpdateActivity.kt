package com.example.voicebrainlive.updates

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
    private var pendingInstallApk: File? = null
    internal var providerFactory: () -> ReleaseProvider = {
        if (BuildConfig.UPDATE_REPOSITORY.isNotBlank() && packageName == "com.example.voicebrainlive")
            GitHubReleaseProvider(repository = BuildConfig.UPDATE_REPOSITORY)
        else UnconfiguredReleaseProvider
    }

    companion object {
        private const val REQUEST_CODE_INSTALL_PERMISSION = 1001
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        ThemeUtils.applyTheme(this)
        super.onCreate(savedInstanceState)
        binding = ActivityAppUpdateBinding.inflate(layoutInflater)
        setContentView(binding.root)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)

        binding.btnBack.setOnClickListener { finish() }

        binding.tvInstalledVersion.text = "Version: ${BuildConfig.VERSION_NAME}\nBuild: ${BuildConfig.VERSION_CODE}\nBuild type: ${BuildConfig.BUILD_TYPE}"
        binding.tvDistributionChannel.text = when {
            BuildConfig.BUILD_TYPE == "agentPilot" -> "Distribution: private agent pilot; not a published release"
            BuildConfig.DEBUG -> "Distribution: test / debug build"
            else -> "Distribution: public GitHub release"
        }

        // Initialize status
        binding.tvUpdateStatus.text = "GitHub public release မှ တိုက်ရိုက် update ရယူနိုင်ပါသည်။ Token မလိုပါ။"

        binding.btnCheckUpdate.setOnClickListener {
            performUpdateCheck()
        }

        binding.btnDownloadInstall.setOnClickListener {
            performDownloadAndInstall()
        }
    }

    private fun getActiveProvider(): ReleaseProvider {
        return providerFactory()
    }

    private fun performUpdateCheck() {
        pendingInstallApk = null
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
                binding.btnDownloadInstall.isEnabled = status is UpdateStatus.Available
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

        binding.tvUpdateStatus.text = when (status) {
            UpdateStatus.NotConfigured -> "No trusted update source configured. Live updates are NOT CONFIGURED."
            UpdateStatus.Current -> "သင့်အက်ပ်သည် နောက်ဆုံးဗားရှင်း ဖြစ်ပါသည် (No newer version is available)."
            UpdateStatus.Failed -> "အပ်ဒိတ် စစ်ဆေး၍မရပါ။ အင်တာနက် သို့မဟုတ် GitHub public release ကို စစ်ဆေးပြီး ခဏကြာမှ ပြန်စမ်းပါ။ Token မလိုပါ။"
            is UpdateStatus.Available -> {
                val rel = status.release
                binding.layoutNewUpdate.visibility = View.VISIBLE
                binding.tvNewVersionInfo.text = "ဗားရှင်း: ${rel.versionName} (Build ${rel.versionCode})\nအရွယ်အစား: %.1f MB".format(rel.size / (1024.0 * 1024.0))

                if (rel.releaseNotes.isNotBlank()) {
                    binding.tvReleaseNotes.text = rel.releaseNotes
                    binding.tvReleaseNotes.visibility = View.VISIBLE
                }

                "Version ${rel.versionName} (${rel.versionCode}) is available. Downloads are ready."
            }
        }
    }

    private fun performDownloadAndInstall() {
        val gitHubProvider = currentProvider as? GitHubReleaseProvider
        val availableUpdate = gitHubProvider?.lastAvailableUpdate

        if (availableUpdate == null) {
            Toast.makeText(this, "ဒေါင်းလုဒ်ဆွဲရန် အချက်အလက် မပြည့်စုံပါ", Toast.LENGTH_SHORT).show()
            return
        }

        binding.btnDownloadInstall.isEnabled = false
        binding.btnCheckUpdate.isEnabled = false
        binding.layoutDownloadProgress.visibility = View.VISIBLE
        binding.progressDownload.progress = 0
        binding.tvDownloadProgress.text = "ဒေါင်းလုဒ် စတင်နေပါသည်..."

        lifecycleScope.launch {
            try {
                val updatesDir = File(cacheDir, "updates").apply { mkdirs() }
                val targetApk = File(updatesDir, "VoiceBrainLive-${availableUpdate.manifest.versionCode}.apk")

                withContext(Dispatchers.IO) {
                    gitHubProvider.gitHubUpdates.download(availableUpdate, targetApk) { downloaded, total ->
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
                    ApkUpdateVerifier.verify(this@AppUpdateActivity, targetApk, availableUpdate.manifest)
                }

                binding.tvDownloadProgress.text = "ဒေါင်းလုဒ် ပြီးစီးပါပြီ။ တပ်ဆင်ရန် ပြင်ဆင်နေပါသည်..."
                promptInstall(targetApk)
            } catch (e: Exception) {
                binding.tvDownloadProgress.text = "ဒေါင်းလုဒ် မအောင်မြင်ပါ: ${e.message}"
                Toast.makeText(this@AppUpdateActivity, "ဒေါင်းလုဒ် မအောင်မြင်ပါ: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.btnDownloadInstall.isEnabled = true
                binding.btnCheckUpdate.isEnabled = true
            }
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
