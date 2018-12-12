package com.example.deepak.camera2

import android.content.Context
import android.content.pm.PackageManager
import android.util.Log
import android.widget.Toast

class UtilsFunctions {

    companion object {
        var log: Boolean = true
        fun eLog(key: String, value: String) {
            if (log)
                Log.e(key, value)
        }

        private fun checkCameraHardware(context: Context): Boolean {
            return context.packageManager.hasSystemFeature(PackageManager.FEATURE_CAMERA)
        }

        fun toast(context: Context, message :String){
            Toast.makeText(context, message, Toast.LENGTH_LONG).show()
        }
    }

}
