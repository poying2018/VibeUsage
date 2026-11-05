package ai.vibecafe.usage.core

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * 自定义背景图持久化。
 *
 * 用户从相册选图后，图片 URI 的读取权限在 app 重启后会失效，
 * 因此先把图复制到 app 私有目录（`getDir("bg")`），再把该绝对路径
 * 存入 SharedPreferences，保证重启后仍可加载。
 */
object BackgroundStore {
    private const val PREFS_NAME = "vibe_usage"
    private const val KEY_BG = "custom_background"
    private const val DIR = "bg"
    private const val FILE = "custom_bg"

    /** 读取已保存的背景图绝对路径，未设置时返回 null。 */
    fun get(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_BG, null)
            ?.takeIf { File(it).exists() }
    }

    /**
     * 把 [uri] 指向的图片复制到私有目录并持久化，返回内部路径；失败返回 null。
     *
     * 复制在 IO 线程做：相册原图动辄 3~8MB，直接在点选回调（主线程）里 copyTo
     * 会卡住一帧甚至数帧，用户看到的就是「点完选图界面，App 顿一下」。
     */
    suspend fun set(context: Context, uri: Uri): String? = withContext(Dispatchers.IO) {
        val dir = context.getDir(DIR, Context.MODE_PRIVATE)
        if (!dir.exists()) dir.mkdirs()
        val out = File(dir, FILE)
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                out.outputStream().use { output -> input.copyTo(output) }
            }
            context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                .edit()
                .putString(KEY_BG, out.absolutePath)
                .apply()
            out.absolutePath
        } catch (_: Exception) {
            null
        }
    }

    /** 清除自定义背景，恢复默认动态光晕。 */
    fun clear(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .remove(KEY_BG)
            .apply()
        try {
            File(context.getDir(DIR, Context.MODE_PRIVATE), FILE).delete()
        } catch (_: Exception) {
            // 忽略删除失败
        }
    }
}
