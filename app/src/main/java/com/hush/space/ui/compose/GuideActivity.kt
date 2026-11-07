package com.hush.space.ui.compose

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.hush.space.core.Prefs

/** 首次启动引导（Compose 版）：三项能力介绍，末页进主界面。 */
class GuideActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            HushThemeFromPrefs { GuideScreen(onFinish = { go() }) }
        }
    }

    private fun go() {
        Prefs.setGuideSeen()
        startActivity(Intent(this, HushMainActivity::class.java))
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
        finish()
    }
}

private data class Feature(val icon: ImageVector, val title: String, val desc: String)

@Composable
private fun GuideScreen(onFinish: () -> Unit) {
    val features = listOf(
        Feature(Icons.Default.Layers, "独立多空间", "同一应用可创建多个副本，各自独立数据，互不干扰"),
        Feature(Icons.Default.Shield, "设备伪装", "按空间自定义机型、品牌与序列号，降低多开检测风险"),
        Feature(Icons.Default.Extension, "模块支持", "内置 Xposed 模块加载能力，导入即用，逐应用控制"),
    )

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(BrandBrush),
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 26.dp)
                .padding(top = 56.dp, bottom = 28.dp),
        ) {
            Text(
                "hush 空间",
                fontSize = 13.sp,
                color = Color.White.copy(alpha = 0.66f),
                fontWeight = FontWeight.Medium,
            )
            Spacer(Modifier.height(14.dp))
            PageIntro(
                title = "欢迎使用 hush 空间",
                desc = "为每个应用准备一份独立、干净、可伪装的运行环境",
                onBrand = true,
            )

            Spacer(Modifier.height(26.dp))

            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                features.forEach { f -> FeatureCard(f) }
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = onFinish,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.White,
                    contentColor = Color(0xFF372C86),
                ),
            ) {
                Text("开始使用", fontWeight = FontWeight.SemiBold)
            }

            TextButton(
                onClick = onFinish,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 2.dp),
            ) {
                Text("跳过", color = Color.White.copy(alpha = 0.72f))
            }
        }
    }
}

@Composable
private fun FeatureCard(f: Feature) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White.copy(alpha = 0.10f), RoundedCornerShape(20.dp))
            .padding(16.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Box(
            modifier = Modifier
                .size(40.dp)
                .background(Color.White.copy(alpha = 0.16f), RoundedCornerShape(13.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Icon(f.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(21.dp))
        }
        Spacer(Modifier.width(14.dp))
        Column {
            Text(
                f.title,
                style = MaterialTheme.typography.titleSmall,
                color = Color.White,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                f.desc,
                style = MaterialTheme.typography.bodySmall,
                color = Color.White.copy(alpha = 0.76f),
                lineHeight = 19.sp,
            )
        }
    }
}
