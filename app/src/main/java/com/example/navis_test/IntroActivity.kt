package com.example.navis_test

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.VideoView

// Màn intro phát introapp.mp4 khi mở app. Video chạy xong (hoặc lỗi/không phát được)
// thì tự chuyển sang MainActivity.
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
        // Không phát được (thiếu codec/lỗi file) thì đừng kẹt ở màn đen — vào thẳng app.
        videoView.setOnErrorListener { _, _, _ ->
            goToMain()
            true
        }
    }

    // Vào MainActivity một lần duy nhất (tránh vừa bấm Skip vừa video kết thúc gọi 2 lần).
    private fun goToMain() {
        if (navigated) return
        navigated = true
        startActivity(Intent(this, MainActivity::class.java))
        finish()
    }
}
