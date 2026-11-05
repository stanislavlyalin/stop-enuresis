package com.stanislavlyalin.stopenuresis.ui

import android.os.Bundle
import android.view.View
import androidx.fragment.app.Fragment
import com.stanislavlyalin.stopenuresis.R

class AboutFragment : Fragment(R.layout.fragment_about) {

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.findViewById<android.widget.TextView>(R.id.aboutVersion).text =
            getString(R.string.appVersion, com.stanislavlyalin.stopenuresis.BuildConfig.VERSION_NAME)
    }
}
