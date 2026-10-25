package com.opensource.routesim.ui

import android.app.Activity
import android.app.Dialog
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.os.Bundle
import android.os.CountDownTimer
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.WindowManager
import androidx.core.widget.doAfterTextChanged
import com.opensource.routesim.R
import com.opensource.routesim.core.SimulationLogger
import com.opensource.routesim.databinding.DialogPrivacyConsentBinding
import kotlin.math.ceil

class PrivacyConsentDialog(
    private val hostActivity: Activity,
    private val onAccepted: () -> Unit,
) : Dialog(hostActivity) {
    private val binding = DialogPrivacyConsentBinding.inflate(layoutInflater)
    private var countdown: CountDownTimer? = null
    private var readTimeComplete = false
    private var reachedBottom = false
    private var countdownStarted = false

    init {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        setContentView(binding.root)
        setCancelable(false)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))

        binding.privacyScroll.setOnScrollChangeListener { view, _, _, _, _ ->
            updateReachedBottom(view)
        }
        binding.etPrivacyConfirm.doAfterTextChanged {
            updateAgreeState()
        }
        binding.btnPrivacyExit.setOnClickListener {
            SimulationLogger.event("privacy_declined")
            dismiss()
            hostActivity.finishAffinity()
        }
        binding.btnPrivacyAgree.setOnClickListener {
            SimulationLogger.event("privacy_accepted")
            dismiss()
            onAccepted()
        }
        updateAgreeState()
    }

    override fun onStart() {
        super.onStart()
        window?.apply {
            setLayout(
                (hostActivity.resources.displayMetrics.widthPixels * 0.9f).toInt(),
                (hostActivity.resources.displayMetrics.heightPixels * 0.84f).toInt(),
            )
            setGravity(Gravity.CENTER)
            addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND)
            setDimAmount(0.62f)
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        binding.privacyScroll.post {
            // Some large displays can fit the full policy without scrolling.
            // Keep an explicit scroll range so "read to the bottom" remains a
            // real, enforceable step on every screen size.
            binding.tvPrivacyPolicy.minimumHeight =
                binding.privacyScroll.height + dp(160)
            binding.privacyScroll.post { updateReachedBottom(binding.privacyScroll) }
        }
        if (!countdownStarted) {
            countdownStarted = true
            startCountdown()
            SimulationLogger.event("privacy_consent_opened")
        }
    }

    override fun dismiss() {
        countdown?.cancel()
        countdown = null
        super.dismiss()
    }

    private fun startCountdown() {
        countdown = object : CountDownTimer(READ_TIME_MILLIS, 250L) {
            override fun onTick(millisUntilFinished: Long) {
                val seconds = ceil(millisUntilFinished / 1_000.0).toInt().coerceAtLeast(0)
                binding.tvPrivacyCountdown.text =
                    hostActivity.getString(R.string.privacy_countdown, seconds)
            }

            override fun onFinish() {
                readTimeComplete = true
                updateCountdownText()
                updateAgreeState()
            }
        }.start()
    }

    private fun updateReachedBottom(scrollView: View) {
        val content = (scrollView as ViewGroup).getChildAt(0) ?: return
        val hasScrollableContent = content.height > scrollView.height
        val atBottom = scrollView.scrollY + scrollView.height >= content.height - dp(6)
        if (atBottom && (hasScrollableContent || readTimeComplete)) {
            reachedBottom = true
        }
        updateAgreeState()
    }

    private fun updateCountdownText() {
        binding.tvPrivacyCountdown.text = when {
            readTimeComplete && reachedBottom -> hostActivity.getString(R.string.privacy_age_ready)
            readTimeComplete -> hostActivity.getString(R.string.privacy_scroll_required)
            else -> binding.tvPrivacyCountdown.text
        }
    }

    private fun updateAgreeState() {
        updateCountdownText()
        val typedCorrectly = binding.etPrivacyConfirm.text?.toString()?.trim() ==
            hostActivity.getString(R.string.privacy_confirm_phrase)
        binding.btnPrivacyAgree.isEnabled = readTimeComplete && reachedBottom && typedCorrectly
    }

    private fun dp(value: Int): Int =
        (value * hostActivity.resources.displayMetrics.density).toInt()

    companion object {
        private const val READ_TIME_MILLIS = 5_000L
    }
}
