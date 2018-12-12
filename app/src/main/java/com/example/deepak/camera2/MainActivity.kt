package com.example.deepak.camera2

import android.content.Intent
import android.os.Bundle
import android.support.v7.app.AppCompatActivity
import com.bumptech.glide.load.engine.DiskCacheStrategy
import com.google.firebase.storage.FirebaseStorage
import kotlinx.android.synthetic.main.activity_main.*

class MainActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        click.setOnClickListener {
            startActivity(Intent(this, CameraShootActivity::class.java))
        }
    }

    override fun onResume() {
        super.onResume()
        try {
            val storage = FirebaseStorage.getInstance()
            val storageRef = storage.reference
            val imageRef = storageRef.child("camera2.jpg")
            GlideApp.with(this)
                .load(imageRef)
                .diskCacheStrategy(DiskCacheStrategy.NONE)
                .skipMemoryCache(true)
                .into(imageView)
        } catch (e: Exception) {
            UtilsFunctions.eLog("exception image", e.toString())
        }
    }
}
