package com.example.navis_test.ui.intro

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.VideoView
import com.example.navis_test.R
import com.example.navis_test.ui.ImmersiveActivity
import com.example.navis_test.ui.main.MainActivity

class IntroActivity : ImmersiveActivity() {

    private var navigated = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_intro)

        val videoView: VideoView = findViewById(R.id.introVideo)
        val uri = Uri.parse("android.resource://$packageName/${R.raw.introapp}")
        videoView.setVideoURI(uri)
        videoView.setOnPreparedListener { it.start() }
        videoView.setOnCompletionListener { goToMain() }

        videoView.setOnErrorListener { _, _, _ ->
            goToMain()
            true
        }
    }

    private fun goToMain() {
        if (navigated) return
        navigated = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
