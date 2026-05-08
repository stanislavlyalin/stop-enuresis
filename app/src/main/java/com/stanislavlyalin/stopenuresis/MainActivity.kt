package com.stanislavlyalin.stopenuresis

import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.android.material.bottomnavigation.BottomNavigationView
import java.io.File

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(
            if (AppSettings.isDarkThemeEnabled(this)) {
                AppCompatDelegate.MODE_NIGHT_YES
            } else {
                AppCompatDelegate.MODE_NIGHT_NO
            }
        )

        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.nav_host_fragment) as NavHostFragment
        val navController = navHostFragment.navController
        val graph = navController.navInflater.inflate(R.navigation.main_nav_graph)
        graph.setStartDestination(getStartDestination())
        navController.graph = graph

        val bottomNavigationView = findViewById<BottomNavigationView>(R.id.bottomNavigation)
        bottomNavigationView.setupWithNavController(navController)
    }

    private fun getStartDestination(): Int {
        if (!AppSettings.isAboutScreenSeen(this)) {
            AppSettings.setAboutScreenSeen(this, true)
            return R.id.aboutFragment
        }

        if (!hasTrainingSamples()) return R.id.dataCollectionFragment
        if (!hasTrainedModel()) return R.id.trainingFragment
        return R.id.usageFragment
    }

    private fun hasTrainingSamples(): Boolean {
        val samplesDir = File(filesDir, "samples")
        return samplesDir.listFiles { file ->
            file.isFile && (
                file.name.endsWith("_1.wav") ||
                    file.name.endsWith("_1_unchecked.wav") ||
                    file.name.endsWith("_0.wav")
                )
        }?.isNotEmpty() == true
    }

    private fun hasTrainedModel(): Boolean {
        return File(filesDir, "model/stopenuresis_model.json").exists()
    }
}
