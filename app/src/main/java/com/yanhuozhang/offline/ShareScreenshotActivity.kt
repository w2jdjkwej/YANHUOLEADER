package com.yanhuozhang.offline

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.IntentCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 系统分享菜单里的“截图记账”入口。
 *
 * 只负责把分享来的图片复制进应用缓存并交给主界面，自身不显示界面；
 * 应用锁、识别和确认都在主界面里完成。
 */
class ShareScreenshotActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val uri = sharedImageUri(intent)
        if (uri == null) {
            finishWithMessage("没有收到图片")
            return
        }
        val inbox = (application as YanHuoApplication).container.sharedScreenshotInbox
        lifecycleScope.launch {
            val file = try {
                withContext(Dispatchers.IO) {
                    contentResolver.openInputStream(uri)?.use(inbox::store)
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                finishWithMessage(error.message ?: "无法读取这张图片")
                return@launch
            }
            if (file == null) {
                finishWithMessage("无法读取这张图片")
                return@launch
            }
            inbox.offer(file)
            startActivity(
                Intent(this@ShareScreenshotActivity, MainActivity::class.java).addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP,
                ),
            )
            finish()
        }
    }

    private fun sharedImageUri(intent: Intent): Uri? {
        if (intent.action != Intent.ACTION_SEND) {
            return null
        }
        return IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            ?: intent.clipData?.takeIf { clip -> clip.itemCount > 0 }?.getItemAt(0)?.uri
    }

    private fun finishWithMessage(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
        finish()
    }
}
