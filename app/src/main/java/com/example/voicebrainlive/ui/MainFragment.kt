package com.example.voicebrainlive.ui

import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.commit
import com.example.voicebrainlive.NotesActivity
import com.example.voicebrainlive.HistoryActivity
import com.example.voicebrainlive.SettingsActivity
import com.example.voicebrainlive.AutomationActivity
import com.example.voicebrainlive.ContactsActivity
import com.example.voicebrainlive.R
import com.example.voicebrainlive.databinding.FragmentMainBinding

class MainFragment : Fragment() {

    private var _binding: FragmentMainBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentMainBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupNavigation()
        setupTopBar()
        
        // Initial fragment
        if (savedInstanceState == null) {
            showFragment(HomeFragment())
        }
    }

    private fun setupNavigation() {
        val beta = activity as? InteractiveBetaHost
        if (beta != null) {
            binding.navHome.setOnClickListener { showFragment(HomeFragment()); updateNavState(0) }
            binding.navChat.setOnClickListener { openInteractiveConversation() }
            binding.tvNavCamera.text = "Memory"
            binding.tvNavAutomation.text = "Tasks"
            binding.navCamera.contentDescription = "Confirmed Memory"
            binding.navAutomation.contentDescription = "Local agent tasks"
            binding.navCamera.setOnClickListener { beta.betaMemory() }
            val words=beta.betaNavLabels()
            binding.tvNavHome.text=words[0];binding.navHome.contentDescription=words[0]
            binding.tvNavChat.text=words[1];binding.navChat.contentDescription=words[1]
            binding.tvNavAutomation.text=words[2];binding.navAutomation.contentDescription=words[2]
            binding.tvNavCamera.text=words[3];binding.navCamera.contentDescription=words[3]
            (binding.root.findViewWithTag<View>("beta_nav_settings") as? android.widget.Button)?.let {it.text=words[4];it.contentDescription=words[4]}
            binding.root.findViewWithTag<View>("beta_nav_settings")?.setOnClickListener { beta.betaSettings() }
            binding.navAutomation.setOnClickListener { beta.betaTasks() }
            return
        }
        binding.navHome.setOnClickListener {
            showFragment(HomeFragment())
            updateNavState(0)
        }

        binding.navChat.setOnClickListener {
            showFragment(ChatSessionFragment())
            updateNavState(1)
        }
        
        binding.navCamera.setOnClickListener {
            showFragment(CameraFragment())
            updateNavState(2)
        }
        
        binding.navAutomation.setOnClickListener {
            startActivity(Intent(requireContext(), AutomationActivity::class.java))
        }
    }

    private fun updateNavState(activeIndex: Int) {
        val context = context ?: return
        val betaColor = if(activity is InteractiveBetaHost) {
            val resolved = android.util.TypedValue()
            context.theme.resolveAttribute(com.google.android.material.R.attr.colorOnSurface, resolved, true)
            resolved.data
        } else null
        val activeColor = betaColor ?: androidx.core.content.ContextCompat.getColor(context, R.color.assistant_primary_light)
        val inactiveColor = betaColor ?: androidx.core.content.ContextCompat.getColor(context, R.color.assistant_text_secondary)
        if(betaColor != null) {
            binding.navHome.isSelected = activeIndex == 0
            binding.navChat.isSelected = activeIndex == 1
        }
        
        binding.ivNavHome.imageTintList = android.content.res.ColorStateList.valueOf(if (activeIndex == 0) activeColor else inactiveColor)
        binding.tvNavHome.setTextColor(if (activeIndex == 0) activeColor else inactiveColor)

        binding.ivNavChat.imageTintList = android.content.res.ColorStateList.valueOf(if (activeIndex == 1) activeColor else inactiveColor)
        binding.tvNavChat.setTextColor(if (activeIndex == 1) activeColor else inactiveColor)

        binding.ivNavCamera.imageTintList = android.content.res.ColorStateList.valueOf(if (activeIndex == 2) activeColor else inactiveColor)
        binding.tvNavCamera.setTextColor(if (activeIndex == 2) activeColor else inactiveColor)
    }

    fun openInteractiveConversation() {
        val beta = activity as? InteractiveBetaHost ?: return
        showFragment(beta.betaConversation()); updateNavState(1)
    }

    private fun setupTopBar() {
        binding.btnAppUpdateTop.setOnClickListener {
            startActivity(Intent(context, com.example.voicebrainlive.updates.AppUpdateActivity::class.java))
        }
        (activity as? InteractiveBetaHost)?.let { beta ->
            binding.btnSettings.contentDescription = "BETA settings"
            binding.btnSettings.setOnClickListener { beta.betaSettings() }
            binding.btnGuide.contentDescription = "Help and tutorial"
            binding.btnGuide.setOnClickListener { beta.betaGuide() }
            return
        }
        binding.btnGuide.setOnClickListener {
            startActivity(Intent(context, com.example.voicebrainlive.GuideActivity::class.java))
        }
        
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(context, SettingsActivity::class.java))
        }
    }

    private fun showFragment(fragment: Fragment) {
        if(activity is InteractiveBetaHost)binding.root.findViewWithTag<View>("beta_app_toolbar")?.visibility=if(fragment is HomeFragment)View.VISIBLE else View.GONE
        childFragmentManager.commit {
            replace(R.id.fragmentHost, fragment)
            setReorderingAllowed(true)
        }
    }

    fun captureVisionFrame() {
        // Agent requests cannot select or transmit an unseen future frame.
        android.widget.Toast.makeText(context, "Open Camera and review a manually captured image before sharing.", android.widget.Toast.LENGTH_LONG).show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
