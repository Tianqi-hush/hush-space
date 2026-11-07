package com.hush.space.ui.compose

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.hush.space.R
import com.hush.space.core.Prefs

/**
 * 启动页：品牌动效 + 路由。
 *
 * 这一页只做两件事：把品牌亮出来，然后在 1.1s 内决定下一个页面是谁。
 * 判断顺序是「权限 → 引导 → 主界面」，后面两个入口都收敛到 HushMainActivity，
 * 所以无论用户从哪条路径进来，看到的都是同一套 MD3 底部导航。
 */
class SplashActivity : ComponentActivity() {

    private val handler = Handler(Looper.getMainLooper())
    private var routed = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HushThemeFromPrefs { SplashScreen() }
        }
        handler.postDelayed({ route() }, 1150)
    }

    private fun route() {
        if (routed || isFinishing) return
        routed = true
        val next = when {
            !hasStoragePermission() && !Prefs.permissionAsked() ->
                Intent(this, PermissionActivity::class.java)
            !Prefs.guideSeen() ->
                Intent(this, GuideActivity::class.java)
            else ->
                Intent(this, HushMainActivity::class.java)
        }
        startActivity(next)
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }

    private fun hasStoragePermission(): Boolean {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(
                this, Manifest.permission.READ_MEDIA_IMAGES
            ) == PackageManager.PERMISSION_GRANTED
        }
        return ContextCompat.checkSelfPermission(
            this, Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }
}

@Composable
private fun SplashScreen() {
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { shown = true }

    val scale by animateFloatAsState(
        targetValue = if (shown) 1f else 0.84f,
        animationSpec = tween(560, easing = FastOutSlowInEasing),
        label = "logoScale",
    )
    val fade by animateFloatAsState(
        targetValue = if (shown) 1f else 0f,
        animationSpec = tween(460),
        label = "logoFade",
    )

    BrandGradient {
        Box(
            modifier = Modifier
                .size(104.dp)
                .scale(scale)
                .alpha(fade)
                .background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(30.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Image(
                painterResource(R.drawable.ic_logo_mark),
                contentDescription = null,
                modifier = Modifier.size(54.dp),
            )
        }

        Spacer(Modifier.height(22.dp))

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                "hush 空间",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold,
                color = Color.White,
                modifier = Modifier.alpha(fade),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                "为每个应用准备一份独立环境",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.72f),
                modifier = Modifier
                    .alpha(fade)
                    .padding(top = 2.dp),
            )
        }
    }
}
