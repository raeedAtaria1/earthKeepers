/*
 * Copyright 2022 The TensorFlow Authors. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *             http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.google.mediapipe.examples.gesturerecognizer

import android.os.Bundle
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.navigation.fragment.NavHostFragment
import androidx.navigation.ui.setupWithNavController
import com.google.mediapipe.examples.gesturerecognizer.databinding.ActivityMainBinding
import android.widget.ImageView
import android.content.Intent
import android.util.Log
import com.google.mediapipe.examples.gesturerecognizer.fragment.CameraFragment

class MainActivity : AppCompatActivity() {
    private lateinit var activityMainBinding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        activityMainBinding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(activityMainBinding.root)

        val navHostFragment =
            supportFragmentManager.findFragmentById(R.id.fragment_container) as NavHostFragment
        val navController = navHostFragment.navController
        // Find the finish_button ImageView
        val finishButton: ImageView = findViewById(R.id.finish_button)

        // Set OnClickListener to navigate to another page when finish_button is pressed
        /*finishButton.setOnClickListener {
            // Replace NextActivity::class.java with the destination activity you want to navigate to
            //val intent = Intent(this, HomeActivity::class.java)
            val intent = Intent(this, SessionSummaryActivity::class.java)
            startActivity(intent)
            finish()
        }*/

        // Set OnClickListener to navigate to SessionSummaryActivity when finish_button is pressed
        finishButton.setOnClickListener {
            Log.d("MainActivity", "Finish button clicked")

            // Retrieve the current fragment from the NavHostFragment
            val currentFragment = navHostFragment.childFragmentManager.primaryNavigationFragment

            if (currentFragment is CameraFragment) {
                Log.d("MainActivity", "CameraFragment found")
                currentFragment.stopCameraAndShowSummary()
            } else {
                Log.e("MainActivity", "CameraFragment not found")
            }
        }

    }


    override fun onBackPressed() {
        finish()
    }
}
