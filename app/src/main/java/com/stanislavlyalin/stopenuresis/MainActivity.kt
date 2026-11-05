package com.stanislavlyalin.stopenuresis

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.Configuration
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.view.View
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import androidx.navigation.ui.NavigationUI
import android.content.res.ColorStateList
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.stanislavlyalin.stopenuresis.training.TrainingRepository
import com.stanislavlyalin.stopenuresis.training.TrainingDatasetStats
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.stanislavlyalin.stopenuresis.service.DataCollectionRecordingService
import com.stanislavlyalin.stopenuresis.training.ExtraTreesModel
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNavigationView: BottomNavigationView
    private lateinit var navController: NavController

    private val reviewPrompt by lazy {
        com.stanislavlyalin.stopenuresis.review.ReviewPrompt(this, {
            navController.currentDestination?.id in setOf(R.id.usageFragment, R.id.aboutFragment) &&
                unavailableDialog?.isShowing != true
        }, { repository().readModelAccuracy() })
    }

    private var datasetCounts = TrainingDatasetStats(0, 0)
    private var modelAvailable = false
    private var unavailableDialog: androidx.appcompat.app.AlertDialog? = null

    private fun repository() = TrainingRepository(AppStorage.samplesDir(this), AppStorage.modelFile(this))

    private val samplesChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DataCollectionRecordingService.ACTION_SAMPLES_CHANGED) return
            refreshNavigationAvailability()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(AppSettings.nightMode(this))

        super.onCreate(savedInstanceState)
        AppStorage.migrateLegacyData(this)
        setContentView(R.layout.activity_main)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        bottomNavigationView = findViewById(R.id.bottomNavigation)
        bottomNavigationView.setupWithNavController(navController)
        bottomNavigationView.itemIconTintList = null
        bottomNavigationView.setOnItemSelectedListener { item ->
            refreshNavigationAvailability()
            if (isAvailable(item.itemId)) NavigationUI.onNavDestinationSelected(item, navController)
            else { showUnavailableDialog(item.itemId); false }
        }
        refreshNavigationAvailability()
        navController.addOnDestinationChangedListener { _, destination, _ ->
            if (!isAvailable(destination.id)) {
                window.decorView.post { refreshNavigationAvailability() }
            }
            reviewPrompt.schedule()
            val tour = destination.id == R.id.onboardingFragment
            bottomNavigationView.visibility = if (tour) View.GONE else View.VISIBLE
            val background = getColor(if (tour) R.color.onboarding_background else R.color.brand_surface)
            window.statusBarColor = background
            window.navigationBarColor = background
            val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !night
                isAppearanceLightNavigationBars = !night
            }
        }
        val restoredDestination = savedInstanceState?.getInt("current_destination", 0)
            ?.takeIf { it in setOf(R.id.aboutFragment, R.id.dataCollectionFragment,
                R.id.trainingFragment, R.id.usageFragment, R.id.settingsFragment, R.id.onboardingFragment) }
        setStartDestination(restoredDestination?.takeIf { isAvailable(it) } ?: getStartDestination())
        updateDataCollectionNavTitle()
    }

    override fun onPostResume() { super.onPostResume(); reviewPrompt.schedule() }

    override fun onPause() { reviewPrompt.pause(); super.onPause() }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) reviewPrompt.schedule() else reviewPrompt.cancel()
    }

    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN) reviewPrompt.touch(true)
        val handled = super.dispatchTouchEvent(event)
        if (event.actionMasked == android.view.MotionEvent.ACTION_UP ||
            event.actionMasked == android.view.MotionEvent.ACTION_CANCEL) reviewPrompt.touch(false)
        return handled
    }

    override fun onSaveInstanceState(outState: Bundle) {
        navController.currentDestination?.id?.let { outState.putInt("current_destination", it) }
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        registerSamplesChangedReceiver()
        refreshNavigationAvailability()
    }

    override fun onStop() {
        unregisterSamplesChangedReceiver()
        super.onStop()
    }

    private fun getStartDestination(): Int {
        if (!AppSettings.isOnboardingCompleted(this)) return R.id.onboardingFragment

        if (modelAvailable) return R.id.usageFragment
        return if (datasetCounts.hasEnoughSamples) R.id.trainingFragment else R.id.dataCollectionFragment
    }

    fun completeOnboarding() {
        AppSettings.completeOnboarding(this)
        refreshNavigationAvailability()
        setStartDestination(getStartDestination())
    }

    private fun setStartDestination(destination: Int) {
        val graph = navController.navInflater.inflate(R.navigation.main_nav_graph)
        graph.setStartDestination(destination)
        navController.graph = graph
    }

    private fun isAvailable(destination: Int): Boolean = when (destination) {
        R.id.trainingFragment -> datasetCounts.hasEnoughSamples
        R.id.usageFragment -> modelAvailable
        else -> true
    }

    fun refreshNavigationAvailability() {
        val repository = repository()
        datasetCounts = repository.getDatasetCounts()
        modelAvailable = repository.hasModel()
        val checked = intArrayOf(android.R.attr.state_checked)
        for (index in 0 until bottomNavigationView.menu.size()) {
            val item = bottomNavigationView.menu.getItem(index)
            val title = getString(when (item.itemId) {
                R.id.trainingFragment -> R.string.bottomNavTraining
                R.id.usageFragment -> R.string.bottomNavUsage
                R.id.aboutFragment -> R.string.bottomNavAbout
                R.id.settingsFragment -> R.string.bottomNavSettings
                else -> R.string.bottomNavCollectData
            })
            val available = isAvailable(item.itemId)
            val gray = getColor(R.color.nav_unavailable)
            item.title = if (available) title else SpannableString(title).apply {
                setSpan(ForegroundColorSpan(gray), 0, length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            androidx.core.view.MenuItemCompat.setContentDescription(item,
                if (available) title else getString(R.string.tabUnavailableDescription, title))
            item.icon = item.icon?.constantState?.newDrawable()?.mutate()?.apply {
                setTintList(if (available) ColorStateList(arrayOf(checked, intArrayOf()),
                    intArrayOf(getColor(R.color.brand_orange), getColor(R.color.ui_secondary)))
                    else ColorStateList.valueOf(gray))
            }
        }
        updateDataCollectionNavTitle()
        navController.currentDestination?.id?.let { current ->
            if (!isAvailable(current)) setStartDestination(getStartDestination())
        }
    }

    private fun showUnavailableDialog(destination: Int) {
        if (unavailableDialog?.isShowing == true) return
        val training = destination == R.id.trainingFragment
        val message = if (training) getString(R.string.trainingUnavailableMessage,
            TrainingRepository.MIN_SAMPLES_PER_CLASS, datasetCounts.positiveCount, datasetCounts.negativeCount)
        else getString(R.string.usageUnavailableMessage) +
            if (datasetCounts.hasEnoughSamples) "" else "\n\n" + getString(R.string.usageNeedsSamplesMessage,
                TrainingRepository.MIN_SAMPLES_PER_CLASS)
        unavailableDialog = MaterialAlertDialogBuilder(this)
            .setBackground(android.graphics.drawable.GradientDrawable().apply {
                setColor(getColor(R.color.brand_surface))
                cornerRadius = 24 * resources.displayMetrics.density
            })
            .setTitle(if (training) R.string.trainingUnavailableTitle else R.string.usageUnavailableTitle)
            .setMessage(message)
            .setPositiveButton(R.string.unavailableOkay, null)
            .show().also { dialog ->
                dialog.findViewById<android.widget.TextView>(android.R.id.message)?.setTextColor(getColor(R.color.ui_secondary))
                dialog.findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)?.setTextColor(getColor(R.color.ui_text))
            }
    }

    override fun onDestroy() {
        reviewPrompt.dispose()
        unavailableDialog?.dismiss()
        unavailableDialog = null
        super.onDestroy()
    }

    private fun registerSamplesChangedReceiver() {
        val filter = IntentFilter(DataCollectionRecordingService.ACTION_SAMPLES_CHANGED)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(samplesChangedReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(samplesChangedReceiver, filter)
        }
    }

    private fun unregisterSamplesChangedReceiver() {
        runCatching { unregisterReceiver(samplesChangedReceiver) }
    }

    private fun updateDataCollectionNavTitle() {
        val pendingSamplesCount = pendingSamplesCount()
        val menuItem = bottomNavigationView.menu.findItem(R.id.dataCollectionFragment)
        menuItem.title = getString(R.string.bottomNavCollectData)
        if (pendingSamplesCount > 0) {
            bottomNavigationView.getOrCreateBadge(R.id.dataCollectionFragment).number = pendingSamplesCount
        } else bottomNavigationView.removeBadge(R.id.dataCollectionFragment)
    }

    private fun pendingSamplesCount(): Int {
        val samplesDir = AppStorage.samplesDir(this)
        return samplesDir.listFiles { file ->
            file.isFile && (
                file.name.endsWith("_1_unchecked.wav") ||
                    file.name.endsWith("_1_detected_unchecked.wav")
                )
        }?.size ?: 0
    }
}
