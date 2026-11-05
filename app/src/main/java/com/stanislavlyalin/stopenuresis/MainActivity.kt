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
import com.google.android.material.bottomnavigation.BottomNavigationView
import com.stanislavlyalin.stopenuresis.service.DataCollectionRecordingService
import com.stanislavlyalin.stopenuresis.training.ExtraTreesModel
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var bottomNavigationView: BottomNavigationView
    private lateinit var navController: NavController

    private val samplesChangedReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != DataCollectionRecordingService.ACTION_SAMPLES_CHANGED) return
            updateDataCollectionNavTitle()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(
            if (AppSettings.isDarkThemeEnabled(this)) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        )

        super.onCreate(savedInstanceState)
        AppStorage.migrateLegacyData(this)
        setContentView(R.layout.activity_main)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        navController = navHostFragment.navController
        bottomNavigationView = findViewById(R.id.bottomNavigation)
        bottomNavigationView.setupWithNavController(navController)
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val tour = destination.id == R.id.onboardingFragment
            bottomNavigationView.visibility = if (tour) View.GONE else View.VISIBLE
            val background = getColor(if (tour) R.color.onboarding_background else R.color.brand_surface)
            window.statusBarColor = background
            window.navigationBarColor = background
            val night = resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
            WindowInsetsControllerCompat(window, window.decorView).apply {
                isAppearanceLightStatusBars = !tour || !night
                isAppearanceLightNavigationBars = !tour || !night
            }
        }
        setStartDestination(getStartDestination())
        updateDataCollectionNavTitle()
    }

    override fun onStart() {
        super.onStart()
        registerSamplesChangedReceiver()
        updateDataCollectionNavTitle()
    }

    override fun onStop() {
        unregisterSamplesChangedReceiver()
        super.onStop()
    }

    private fun getStartDestination(): Int {
        if (!AppSettings.isOnboardingCompleted(this)) return R.id.onboardingFragment

        if (!hasTrainingSamples()) return R.id.dataCollectionFragment
        if (!hasTrainedModel()) return R.id.trainingFragment
        return R.id.usageFragment
    }

    fun completeOnboarding() {
        AppSettings.completeOnboarding(this)
        setStartDestination(R.id.dataCollectionFragment)
    }

    private fun setStartDestination(destination: Int) {
        val graph = navController.navInflater.inflate(R.navigation.main_nav_graph)
        graph.setStartDestination(destination)
        navController.graph = graph
    }

    private fun hasTrainingSamples(): Boolean {
        val samplesDir = AppStorage.samplesDir(this)
        return samplesDir.listFiles { file ->
            file.isFile && (
                file.name.endsWith("_1.wav") ||
                    file.name.endsWith("_1_unchecked.wav") ||
                    file.name.endsWith("_1_detected_unchecked.wav") ||
                    file.name.endsWith("_0.wav")
                )
        }?.isNotEmpty() == true
    }

    private fun hasTrainedModel(): Boolean {
        val modelFile = AppStorage.modelFile(this)
        if (!modelFile.exists()) return false
        return runCatching {
            ExtraTreesModel.fromJson(JSONObject(modelFile.readText()))
        }.isSuccess
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
        menuItem.title = if (pendingSamplesCount > 0) {
            buildDataCollectionNavTitle(pendingSamplesCount)
        } else {
            getString(R.string.bottomNavCollectData)
        }
    }

    private fun buildDataCollectionNavTitle(pendingSamplesCount: Int): CharSequence {
        val baseTitle = getString(R.string.bottomNavCollectData)
        val counter = getString(R.string.bottomNavCollectDataCounter, pendingSamplesCount)
        return SpannableString(baseTitle + counter).apply {
            setSpan(
                ForegroundColorSpan(Color.RED),
                baseTitle.length,
                length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
            )
        }
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
