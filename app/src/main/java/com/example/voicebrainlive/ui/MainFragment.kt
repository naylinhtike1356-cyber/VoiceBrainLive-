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
        val activeColor = androidx.core.content.ContextCompat.getColor(context, R.color.assistant_primary_light)
        val inactiveColor = androidx.core.content.ContextCompat.getColor(context, R.color.assistant_text_secondary)
        
        binding.ivNavHome.imageTintList = android.content.res.ColorStateList.valueOf(if (activeIndex == 0) activeColor else inactiveColor)
        binding.tvNavHome.setTextColor(if (activeIndex == 0) activeColor else inactiveColor)

        binding.ivNavChat.imageTintList = android.content.res.ColorStateList.valueOf(if (activeIndex == 1) activeColor else inactiveColor)
        binding.tvNavChat.setTextColor(if (activeIndex == 1) activeColor else inactiveColor)

        binding.ivNavCamera.imageTintList = android.content.res.ColorStateList.valueOf(if (activeIndex == 2) activeColor else inactiveColor)
        binding.tvNavCamera.setTextColor(if (activeIndex == 2) activeColor else inactiveColor)
    }

    private fun setupTopBar() {
        binding.btnGuide.setOnClickListener {
            startActivity(Intent(context, com.example.voicebrainlive.GuideActivity::class.java))
        }
        
        binding.btnSettings.setOnClickListener {
            startActivity(Intent(context, SettingsActivity::class.java))
        }
    }

    private fun showFragment(fragment: Fragment) {
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
