package com.stanislavlyalin.stopenuresis.ui

import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.fragment.app.Fragment
import androidx.recyclerview.widget.RecyclerView
import androidx.viewpager2.widget.ViewPager2
import com.google.android.material.button.MaterialButton
import com.stanislavlyalin.stopenuresis.MainActivity
import com.stanislavlyalin.stopenuresis.R

class OnboardingFragment : Fragment(R.layout.fragment_onboarding) {
    private lateinit var pager: ViewPager2
    private lateinit var dots: LinearLayout
    private lateinit var next: MaterialButton
    private lateinit var back: OnBackPressedCallback
    private var currentPage = 0

    private val pageChanged = object : ViewPager2.OnPageChangeCallback() {
        override fun onPageSelected(position: Int) = showPage(position)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        view.background = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(
            ContextCompat.getColor(requireContext(), R.color.onboarding_background),
            ContextCompat.getColor(requireContext(), R.color.onboarding_background_end)
        ))
        pager = view.findViewById(R.id.onboardingPager)
        dots = view.findViewById(R.id.onboardingDots)
        next = view.findViewById(R.id.onboardingNext)
        repeat(slides.size) {
            dots.addView(View(requireContext()).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { marginEnd = dp(6) })
        }
        back = object : OnBackPressedCallback(false) {
            override fun handleOnBackPressed() {
                pager.setCurrentItem(pager.currentItem - 1, true)
            }
        }
        requireActivity().onBackPressedDispatcher.addCallback(viewLifecycleOwner, back)
        pager.adapter = SlideAdapter()
        currentPage = savedInstanceState?.getInt(KEY_PAGE) ?: 0
        pager.setCurrentItem(currentPage, false)
        pager.registerOnPageChangeCallback(pageChanged)
        showPage(currentPage)
        view.findViewById<MaterialButton>(R.id.onboardingSkip).setOnClickListener { finishTour() }
        next.setOnClickListener {
            if (pager.currentItem == slides.lastIndex) finishTour()
            else pager.setCurrentItem(pager.currentItem + 1, true)
        }
    }

    private fun showPage(position: Int) {
        currentPage = position
        back.isEnabled = position > 0
        next.setText(if (position == slides.lastIndex) R.string.onboardingFinish else R.string.onboardingNext)
        dots.contentDescription = getString(R.string.onboardingPage, position + 1, slides.size)
        // ViewPager2 может вызвать обработчик во время размещения: состав дочерних View уже готов.
        slides.indices.forEach { index ->
            dots.getChildAt(index).background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(ContextCompat.getColor(requireContext(), if (index == position)
                    R.color.brand_orange else R.color.onboarding_dot_inactive))
            }
        }
    }

    private fun finishTour() = (requireActivity() as MainActivity).completeOnboarding()

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt(KEY_PAGE, currentPage)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroyView() {
        pager.unregisterOnPageChangeCallback(pageChanged)
        pager.adapter = null
        super.onDestroyView()
    }

    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()

    private data class Slide(val title: Int, val body: Int, val hint: Int, val image: Int)

    private class SlideAdapter : RecyclerView.Adapter<SlideAdapter.Holder>() {
        class Holder(view: View) : RecyclerView.ViewHolder(view)

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder =
            Holder(LayoutInflater.from(parent.context).inflate(R.layout.item_onboarding, parent, false))

        override fun getItemCount() = slides.size

        override fun onBindViewHolder(holder: Holder, position: Int) {
            val slide = slides[position]
            holder.itemView.apply {
                findViewById<TextView>(R.id.onboardingTitle).apply {
                    setText(slide.title)
                    ViewCompat.setAccessibilityHeading(this, true)
                }
                findViewById<TextView>(R.id.onboardingBody).setText(slide.body)
                findViewById<TextView>(R.id.onboardingHint).setText(slide.hint)
                findViewById<ImageView>(R.id.onboardingIllustration).setImageResource(slide.image)
                scrollTo(0, 0)
            }
        }
    }

    companion object {
        private const val KEY_PAGE = "onboarding_page"
        private val slides = listOf(
            Slide(R.string.onboardingWelcomeTitle, R.string.onboardingWelcomeBody, R.string.onboardingWelcomeHint, R.drawable.onboarding_welcome),
            Slide(R.string.onboardingSamplesTitle, R.string.onboardingSamplesBody, R.string.onboardingSamplesHint, R.drawable.onboarding_samples),
            Slide(R.string.onboardingTrainingTitle, R.string.onboardingTrainingBody, R.string.onboardingTrainingHint, R.drawable.onboarding_training),
            Slide(R.string.onboardingBedtimeTitle, R.string.onboardingBedtimeBody, R.string.onboardingBedtimeHint, R.drawable.onboarding_bedtime),
            Slide(R.string.onboardingAlarmTitle, R.string.onboardingAlarmBody, R.string.onboardingAlarmHint, R.drawable.onboarding_alarm)
        )
    }
}
