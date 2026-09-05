/**
 * 职责：全屏相机扫码层——CameraX 取流 + zxing 解 QR 码；扫到文本（通常是 otpauth:// 链接）
 *       即回调，由编辑页回填密钥框。权限申请、相机绑定、解码线程的生命周期全在本文件自管理。
 * 架构位置：编辑页「扫码」按钮盖层弹出（盖在当前页上方，非独立路由页，编辑状态不丢）。
 * Python 类比：AndroidView 互操作 ≈ 在声明式 UI 里嵌一块传统命令式控件（此处的 PreviewView）；
 *           帧解码跑在专用单线程 executor 上，≈ 一个串行消费队列的工作线程。
 */
package com.copy.account.page

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.view.WindowManager
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.copy.account.ui.components.TextActionButton
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.PlanarYUVLuminanceSource
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** 全屏相机扫码层：盖在当前页上方，扫码回调文本、由编辑页回填密钥框。 */
@Composable
internal fun ScanQrOverlay(onScanned: (String) -> Unit, onClose: () -> Unit) {
    val context = LocalContext.current
    val window = (context as? Activity)?.window
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var scanError by remember { mutableStateOf<String?>(null) }
    // 运行时权限：Android 危险权限（相机等）须运行时弹窗征得用户同意；
    // launch() 发出请求，用户选择完在回调里收结果（true/false）。
    val requestPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok -> granted = ok }
    val owner = remember { context as? LifecycleOwner }
    val previewView = remember {
        PreviewView(context).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }
    val decodeExecutor = remember { Executors.newSingleThreadExecutor() }
    val mainHandler = remember { Handler(Looper.getMainLooper()) }
    // 跨线程原子标志：解码线程 compareAndSet(true, false) 保证「只回调一次」——首帧命中后其余帧全跳过。
    val scanning = remember { AtomicBoolean(true) }

    // 扫码层只显示相机画面，不露敏感内容：临时期清除窗 FLAG_SECURE，离层时还原。
    // 否则部分 OEM 在安全窗口下相机预览不输出（黑屏）。
    DisposableEffect(Unit) {
        val hadSecure = window?.attributes?.flags?.and(WindowManager.LayoutParams.FLAG_SECURE) != 0
        if (hadSecure) window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        onDispose {
            if (hadSecure) window?.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)
            provider?.unbindAll()
            decodeExecutor.shutdownNow()
        }
    }
    BackHandler { onClose() }

    LaunchedEffect(Unit) {
        if (!granted) requestPermission.launch(Manifest.permission.CAMERA)
    }
    LaunchedEffect(granted, owner) {
        if (!granted || owner == null) return@LaunchedEffect
        val instance = withContext(Dispatchers.IO) {
            runCatching { ProcessCameraProvider.getInstance(context).get() }.getOrNull()
        }
        if (instance == null) {
            scanError = "相机不可用，请改用手动输入密钥。"
            return@LaunchedEffect
        }
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val analysis = ImageAnalysis.Builder()
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
        val frameCounter = AtomicInteger(0)
        // 解码器与提示整场复用：解码跑在单线程 executor 上，无并发之争。
        val reader = QRCodeReader()
        val decodeHints = mapOf(DecodeHintType.TRY_HARDER to true)
        analysis.setAnalyzer(decodeExecutor) { image ->
            try {
                if (scanning.get()) {
                    // 每 4 帧解一次即可；对不上的普通画面自然跳过。
                    val luminance = if (frameCounter.incrementAndGet() % 4 == 0) luminanceOf(image) else null
                    val text = luminance?.let { decodeQr(it, reader, decodeHints) }
                    if (text != null && scanning.compareAndSet(true, false)) {
                        // 解码在工作线程；onScanned 更新 Compose 状态，必须切回主线程调。
                        mainHandler.post { onScanned(text) }
                    }
                }
            } finally {
                image.close()
            }
        }
        runCatching {
            instance.unbindAll()
            instance.bindToLifecycle(owner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
        }.onSuccess { provider = instance }
            .onFailure { scanError = "相机启动失败：${it.message ?: "未知原因"}" }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        when {
            !granted -> Column(
                Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("需要相机权限", color = Color.White)
                Text(
                    "扫码读取两步验证二维码需要相机权限。\n拒绝后仍可手动粘贴密钥。",
                    color = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.padding(top = 8.dp)
                )
                Row(
                    Modifier.padding(top = 16.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    TextActionButton("取消", onClick = onClose, textColor = Color.White)
                    TextActionButton("授予权限", onClick = { requestPermission.launch(Manifest.permission.CAMERA) }, textColor = Color.White)
                }
            }
            scanError != null -> Column(
                Modifier.align(Alignment.Center).padding(horizontal = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(scanError.orEmpty(), color = Color.White)
                TextActionButton("关闭", onClick = onClose, modifier = Modifier.padding(top = 16.dp), textColor = Color.White)
            }
            else -> {
                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    Modifier
                        .align(Alignment.Center)
                        .size(250.dp)
                        .border(2.dp, Color.White.copy(alpha = 0.85f))
                )
                Text(
                    "将两步验证二维码对准框内",
                    color = Color.White,
                    modifier = Modifier.align(Alignment.TopCenter).padding(top = 60.dp)
                )
                TextActionButton(
                    "关闭",
                    onClick = onClose,
                    modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 40.dp),
                    textColor = Color.White
                )
            }
        }
    }
}

/** Y 平面亮度 + 旋转后的尺寸。 */
private class Luminance(val data: ByteArray, val width: Int, val height: Int)

private fun luminanceOf(image: ImageProxy): Luminance {
    val y = image.planes[0]
    val buffer = y.buffer
    val rowStride = y.rowStride
    val pixelStride = y.pixelStride
    val width = image.width
    val height = image.height
    buffer.rewind()
    val gray = ByteArray(width * height)
    if (rowStride == width && pixelStride == 1) {
        if (buffer.remaining() >= width * height) {
            buffer.get(gray)
        }
    } else {
        val row = ByteArray(rowStride)
        var offset = 0
        for (r in 0 until height) {
            buffer.position(r * rowStride)
            buffer.get(row, 0, rowStride)
            if (pixelStride == 1) {
                System.arraycopy(row, 0, gray, offset, width)
            } else {
                for (c in 0 until width) gray[offset + c] = row[c * pixelStride]
            }
            offset += width
        }
    }
    return when (image.imageInfo.rotationDegrees % 360) {
        90 -> rotatedTo(gray, width, height, height, width) { x, y -> x * height + (height - 1 - y) }
        180 -> rotatedTo(gray, width, height, width, height) { x, y -> (height - 1 - y) * width + (width - 1 - x) }
        270 -> rotatedTo(gray, width, height, height, width) { x, y -> (width - 1 - x) * height + y }
        else -> Luminance(gray, width, height)
    }
}

/** 按映射把灰度旋转成竖直方向；旋转 90/270 时目标宽高互换（outW/outH）。 */
private fun rotatedTo(src: ByteArray, w: Int, h: Int, outW: Int, outH: Int, index: (x: Int, y: Int) -> Int): Luminance {
    val out = ByteArray(src.size)
    for (y in 0 until h) {
        for (x in 0 until w) out[index(x, y)] = src[y * w + x]
    }
    return Luminance(out, outW, outH)
}

private fun decodeQr(lum: Luminance, reader: QRCodeReader, hints: Map<DecodeHintType, *>): String? {
    val source = PlanarYUVLuminanceSource(lum.data, lum.width, lum.height, 0, 0, lum.width, lum.height, false)
    val bitmap = BinaryBitmap(HybridBinarizer(source))
    return try {
        reader.decode(bitmap, hints).text
    } catch (_: Exception) {
        null
    } finally {
        reader.reset()
    }
}
