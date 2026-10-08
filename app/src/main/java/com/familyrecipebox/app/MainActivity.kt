package com.familyrecipebox.app

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.familyrecipebox.app.ui.theme.RecipeCardsTheme
import com.familyrecipebox.app.util.BootTrace

class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        BootTrace.stage("MainActivity.onCreate")

        enableEdgeToEdge()
        setContent {
            RecipeCardsTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    RecipeCardsApp()
                }
            }
        }

        // 界面搭起来了；「启动是否真正走完」由 RecipeCardsApp 首次组合时标记
        BootTrace.stage("MainActivity.setContent")
    }
}
