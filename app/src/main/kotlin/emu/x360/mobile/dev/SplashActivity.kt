package emu.x360.mobile.dev

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import emu.x360.mobile.dev.bootstrap.AppRuntimeManager
import emu.x360.mobile.dev.ui.theme.X360RebuildTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SplashActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            X360RebuildTheme {
                SplashScreen()
            }
        }
        lifecycleScope.launch {
            val startedAt = System.currentTimeMillis()
            withContext(Dispatchers.IO) {
                AppRuntimeManager(applicationContext).shellSnapshot(
                    lastAction = "Splash bootstrap",
                    autoPrepareRuntime = false,
                )
            }
            val elapsed = System.currentTimeMillis() - startedAt
            val remaining = (900L - elapsed).coerceAtLeast(0L)
            delay(remaining)
            startActivity(
                Intent(this@SplashActivity, MainActivity::class.java).apply {
                    data = intent?.data
                    action = intent?.action
                    type = intent?.type
                    intent?.extras?.let { putExtras(it) }
                },
            )
            finish()
        }
    }
}

@Composable
private fun SplashScreen() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                brush = Brush.radialGradient(
                    colors = listOf(Color(0xFF163E52), Color(0xFF09141C), Color(0xFF05090C)),
                ),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier.padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = "X360 Mobile",
                style = MaterialTheme.typography.headlineLarge,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFF4F8FB),
            )
            Text(
                text = "0.2.0 alpha",
                style = MaterialTheme.typography.titleMedium,
                color = Color(0xFF9AD9FF),
            )
            Text(
                text = "Android shell for the reconstructed Xenia + FEX + Turnip runtime",
                style = MaterialTheme.typography.bodyMedium,
                color = Color(0xFFD1DAE2),
            )
        }
    }
}
