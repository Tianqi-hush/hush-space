package com.hush.space.ui.compose

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.hush.space.R
import com.hush.space.core.AppRepo
import com.hush.space.core.IconStore
import com.hush.space.ui.CropImageView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.InputStream

/**
 * 图标裁剪（Compose 外壳 + 自绘裁剪控件）。
 *
 * 裁剪区需要拖动/缩放手势与双指缩放，继续用 [CropImageView] 这个自绘 View；
 * 外壳（顶栏、按钮、留白）走 MD3，跟其余页面保持一致。
 */
class IconCropActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PACKAGE = "pkg"
        const val EXTRA_IMAGE = "image"
        internal const val OUTPUT_SIZE = 512
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PACKAGE)
        val image = intent.getStringExtra(EXTRA_IMAGE)
        if (pkg == null || image == null) {
            finish()
            return
        }

        setContent {
            HushThemeFromPrefs {
                CropScreen(
                    packageName = pkg,
                    imageUri = Uri.parse(image),
                    onDone = { finish() },
                )
            }
        }
    }
}

@Composable
private fun CropScreen(packageName: String, imageUri: Uri, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()

    var cropView by remember { mutableStateOf<CropImageView?>(null) }
    var bitmap by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(true) }
    var saving by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()

    // 按边长采样到 2048 以内，避免超大图直接 OOM。
    androidx.compose.runtime.LaunchedEffect(imageUri) {
        loading = true
        val bmp = withContext(Dispatchers.IO) {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                ctx.contentResolver.openInputStream(imageUri)?.use {
                    BitmapFactory.decodeStream(it, null, bounds)
                }
                var sample = 1
                var maxSide = maxOf(bounds.outWidth, bounds.outHeight)
                while (maxSide / sample > 2048) sample *= 2
                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                ctx.contentResolver.openInputStream(imageUri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                }
            } catch (t: Throwable) {
                null
            }
        }
        loading = false
        if (bmp == null) {
            toast(ctx.getString(R.string.crop_load_fail))
            onDone()
            return@LaunchedEffect
        }
        bitmap = bmp
        cropView?.setImage(bmp)
    }

    HushPage(
        title = stringResource(R.string.act_change_icon),
        onBack = onDone,
    ) { inner ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(inner)
                .padding(horizontal = 20.dp, vertical = 12.dp),
        ) {
            Text(
                "拖动调整位置，双指缩放，取中间正方形区域",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .padding(top = 12.dp),
                shape = RoundedCornerShape(24.dp),
                color = MaterialTheme.colorScheme.surfaceContainerHigh,
            ) {
                Box(contentAlignment = Alignment.Center) {
                    AndroidView(
                        factory = { c ->
                            CropImageView(c).also {
                                cropView = it
                                bitmap?.let { b -> it.setImage(b) }
                            }
                        },
                        modifier = Modifier.fillMaxSize(),
                    )
                    if (loading) {
                        CircularProgressIndicator(strokeWidth = 2.dp)
                    }
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedButton(
                    onClick = { cropView?.reset() },
                    modifier = Modifier
                        .weight(1f)
                        .height(48.dp),
                ) {
                    Text("复位")
                }
                Button(
                    onClick = {
                        val view = cropView ?: return@Button
                        if (!view.hasImage()) return@Button
                        val cropped = try {
                            view.crop(IconCropActivity.OUTPUT_SIZE)
                        } catch (t: Throwable) {
                            null
                        }
                        if (cropped == null) {
                            toast(ctx.getString(R.string.crop_save_fail))
                            return@Button
                        }
                        saving = true
                        scope.launch {
                            val ok = withContext(Dispatchers.IO) {
                                val r = IconStore.saveIcon(ctx, packageName, cropped)
                                AppRepo.invalidateIcon(packageName)
                                r
                            }
                            saving = false
                            toast(ctx.getString(if (ok) R.string.crop_saved else R.string.crop_save_fail))
                            if (ok) onDone()
                        }
                    },
                    enabled = !saving,
                    modifier = Modifier
                        .weight(1.4f)
                        .height(48.dp),
                ) {
                    if (saving) {
                        CircularProgressIndicator(
                            modifier = Modifier.height(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("保存图标")
                    }
                }
            }

            androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
        }
    }
}
