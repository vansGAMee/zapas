package app.zapas

import android.app.Activity
import android.app.Instrumentation
import android.content.Intent
import android.os.Bundle
import kotlinx.coroutines.runBlocking
import java.io.File
import java.security.MessageDigest

/** Executes the actual Android storage, Keystore, network and sync stack. */
class VerificationRunner : Instrumentation() {
    private lateinit var arguments: Bundle
    override fun onCreate(arguments: Bundle?) { this.arguments = arguments ?: Bundle(); start() }

    override fun onStart() {
        val results = Bundle()
        try {
            runBlocking {
                val repo = targetContext.repo()
                check(android.os.Build.HARDWARE in setOf("ranchu", "goldfish") || android.os.Build.FINGERPRINT.contains("generic") || android.os.Build.MODEL.contains("sdk")) { "Run fixture tests only on a disposable emulator" }
                repo.prefs.edit().putBoolean("auto_sync", false).putBoolean("delete_watched",false).putInt("max_videos", 2).putInt("max_mb", 512).commit()
                repo.videos.value.forEach { repo.markWatched(it.id,false) }
                val address = arguments.getString("address") ?: error("Missing address")
                val code = arguments.getString("code") ?: error("Missing pairing code")
                repo.pair(address, code)
                check(repo.paired) { "Pairing did not persist credentials" }
                repo.sync()
                val ready = repo.videos.value.filter { it.downloaded }
                check(ready.size == 2) { "Expected 2 downloaded videos: ${repo.status.value}" }
                ready.forEach { v ->
                    check(repo.thumb(v.id).length() > 0) { "Missing offline thumbnail" }
                    val hash = MessageDigest.getInstance("SHA-256").digest(repo.media(v.id).readBytes()).joinToString("") { "%02x".format(it) }
                    check(hash == v.sha) { "File integrity mismatch" }
                }
                // Resume a real partial file, then verify hash again.
                val v = ready.first()
                val bytes = repo.media(v.id).readBytes()
                File(repo.mediaDir, "${v.id}.part").writeBytes(bytes.copyOfRange(0, bytes.size / 3))
                File(repo.mediaDir, "${v.id}.part.sha").writeText(v.sha)
                check(repo.media(v.id).delete())
                repo.reload()
                repo.sync()
                check(repo.media(v.id).readBytes().contentEquals(bytes)) { "Resumed file differs" }
                check(!File(repo.mediaDir, "${v.id}.part").exists())
                repo.progress(v.id, 3200)
                if(!repo.videos.value.first{it.id==v.id}.pinned)repo.pin(v.id)
                repo.like(v.id);repo.later(v.id);repo.playlist(v.id,"В дорогу")
                val reopened = Repository(targetContext)
                check(reopened.paired) { "Android Keystore credential did not survive reopen" }
                check(reopened.videos.value.first { it.id == v.id }.position == 3200L)
                check(reopened.videos.value.first { it.id == v.id }.pinned)
                check(reopened.videos.value.first { it.id == v.id }.playlist=="В дорогу")
                val sample=v.copy(title="Линукс для начинающих",channel="Технологии",duration=300.0,watched=false,bytes=1000)
                check(SmartSearch.find(listOf(sample),"линукс короткие").size==1)
                check(SmartSearch.find(listOf(sample),"линус до 10 мин").size==1)
                check(SmartSearch.find(listOf(sample),"длинные").isEmpty())
                val limits=DownloadLimits(10,500000,1000,2000,600.0)
                check(limits.reason(sample,0,0,500000,0)==null)
                check(limits.reason(sample,10,0,50000,0)!=null)
                check(limits.reason(sample,0,0,1500,0)!=null)
                check(limits.reason(sample.copy(bytes=4000),0,0,50000,0)!=null)
                results.putString("stream", "ANDROID_SYNC_OK: pair, Wi-Fi transfer, thumbnails, SHA-256, Range resume, Keystore, persisted progress/playlists, typo search and storage limits\n")
            }
            startActivitySync(Intent(targetContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            finish(Activity.RESULT_OK, results)
        } catch (e: Throwable) {
            results.putString("stream", "ANDROID_SYNC_FAILED: ${e.stackTraceToString()}\n")
            finish(Activity.RESULT_CANCELED, results)
        }
    }
}
