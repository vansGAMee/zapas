package app.zapas

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.URI
import java.net.URL
import java.security.KeyStore
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

data class Video(
    val id: String, val title: String, val channel: String, val duration: Double,
    val bytes: Long, val sha: String, val height: Int, val codec: String,
    val downloaded: Boolean, val position: Long, val watched: Boolean,
    val pinned: Boolean, val updated: Double, val created: Double, val error: String = "",
    val liked: Boolean = false, val later: Boolean = false, val playlist: String = ""
)

class LibraryDb(context: Context) : SQLiteOpenHelper(context, "library.db", null, 3) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("""CREATE TABLE videos(id TEXT PRIMARY KEY,title TEXT NOT NULL,channel TEXT NOT NULL,
            duration REAL NOT NULL,bytes INTEGER NOT NULL,sha TEXT NOT NULL,height INTEGER NOT NULL,
            codec TEXT NOT NULL,downloaded INTEGER DEFAULT 0,position INTEGER DEFAULT 0,
            watched INTEGER DEFAULT 0,pinned INTEGER DEFAULT 0,updated REAL DEFAULT 0,created REAL NOT NULL,error TEXT DEFAULT '',liked INTEGER DEFAULT 0,later INTEGER DEFAULT 0,playlist TEXT DEFAULT '')""")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
        if(oldVersion<2)db.execSQL("ALTER TABLE videos ADD COLUMN error TEXT DEFAULT ''")
        if(oldVersion<3){db.execSQL("ALTER TABLE videos ADD COLUMN liked INTEGER DEFAULT 0");db.execSQL("ALTER TABLE videos ADD COLUMN later INTEGER DEFAULT 0");db.execSQL("ALTER TABLE videos ADD COLUMN playlist TEXT DEFAULT ''")}
    }
}

class TokenVault(context: Context) {
    private val prefs = context.getSharedPreferences("secrets", Context.MODE_PRIVATE)
    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey("zapas-device", null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder("zapas-device", KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun save(value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        prefs.edit().putString("token", Base64.encodeToString(cipher.iv + cipher.doFinal(value.toByteArray()), Base64.NO_WRAP)).commit()
    }
    fun read(): String = runCatching {
        val data = Base64.decode(prefs.getString("token", ""), Base64.NO_WRAP)
        if (data.size < 13) return ""
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, data.copyOfRange(0, 12))) }
        String(cipher.doFinal(data.copyOfRange(12, data.size)))
    }.getOrDefault("")
    fun clear() { prefs.edit().clear().commit() }
}

class Repository(val context: Context) {
    private val db = LibraryDb(context).writableDatabase
    val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)
    private val vault = TokenVault(context)
    private val localNetwork = LocalNetwork(context)
    val videos = MutableStateFlow<List<Video>>(emptyList())
    val status = MutableStateFlow("Готово к просмотру")
    val syncing = MutableStateFlow(false)
    val transfer = MutableStateFlow(0f)
    val mutex = Mutex()
    val mediaDir = File(context.filesDir, "media").apply { mkdirs() }
    private val thumbs = File(context.filesDir, "thumbs").apply { mkdirs() }
    @Volatile var playingId: String? = null
    val sleepDeadline = MutableStateFlow(0L)
    val phonePaused get() = prefs.getBoolean("downloads_paused", false)
    val paired get() = prefs.getString("address", "").orEmpty().isNotEmpty() && vault.read().isNotEmpty()
    val address get() = prefs.getString("address", "").orEmpty()
    fun media(id: String) = File(mediaDir, "$id.mp4")
    fun thumb(id: String) = File(thumbs, "$id.jpg")

    init { reload() }

    fun reload() {
        val rows = mutableListOf<Video>()
        db.rawQuery("SELECT * FROM videos ORDER BY pinned DESC,created DESC", null).use { c ->
            fun s(k: String) = c.getString(c.getColumnIndexOrThrow(k))
            fun n(k: String) = c.getLong(c.getColumnIndexOrThrow(k))
            fun d(k: String) = c.getDouble(c.getColumnIndexOrThrow(k))
            while (c.moveToNext()) rows += Video(s("id"), s("title"), s("channel"), d("duration"), n("bytes"), s("sha"), n("height").toInt(), s("codec"), n("downloaded") == 1L && media(s("id")).exists(), n("position"), n("watched") == 1L, n("pinned") == 1L, d("updated"), d("created"), s("error"),n("liked")==1L,n("later")==1L,s("playlist"))
        }
        videos.value = rows
    }

    fun progress(id: String, positionMs: Long, ended: Boolean = false) {
        val v = videos.value.find { it.id == id } ?: return
        db.update("videos", ContentValues().apply {
            put("position", positionMs.coerceAtLeast(0)); put("watched", if (ended || positionMs >= v.duration * 950) 1 else 0)
            put("updated", System.currentTimeMillis() / 1000.0)
        }, "id=?", arrayOf(id))
        reload()
    }

    fun pin(id: String) {
        db.execSQL("UPDATE videos SET pinned=1-pinned WHERE id=?", arrayOf(id)); reload()
    }

    fun like(id:String){db.execSQL("UPDATE videos SET liked=1-liked WHERE id=?",arrayOf(id));reload()}
    fun later(id:String){db.execSQL("UPDATE videos SET later=1-later WHERE id=?",arrayOf(id));reload()}
    fun playlist(id:String,name:String){db.update("videos",ContentValues().apply{put("playlist",name.trim().take(60))},"id=?",arrayOf(id));reload()}
    fun markWatched(id:String,watched:Boolean){db.execSQL("UPDATE videos SET watched=?,position=0,updated=? WHERE id=?",arrayOf<Any>(if(watched)1 else 0,System.currentTimeMillis()/1000.0,id));reload()}
    fun space():SpaceUsage=SpaceUsage(mediaDir.listFiles()?.filter{it.extension=="mp4"}?.sumOf{it.length()}?:0,mediaDir.listFiles()?.filter{it.extension=="part"}?.sumOf{it.length()}?:0,thumbs.listFiles()?.sumOf{it.length()}?:0,mediaDir.usableSpace)
    fun limits():DownloadLimits {
        val usage=space();val reserve=prefs.getInt("reserve_mb",1024).toLong()*1024*1024
        val budget=if(prefs.getBoolean("auto_budget",false)) ((usage.free+usage.used-reserve).coerceAtLeast(0)*prefs.getInt("budget_percent",10)/100) else prefs.getInt("max_mb",8192).toLong()*1024*1024
        return DownloadLimits(prefs.getInt("max_videos",50),budget,reserve,prefs.getInt("max_item_mb",0).toLong()*1024*1024,prefs.getInt("max_duration_min",0)*60.0)
    }
    fun pauseDownloads(paused:Boolean){prefs.edit().putBoolean("downloads_paused",paused).commit();if(paused){SyncWorker.cancel(context);status.value="Загрузки на паузе. Сохранённое доступно офлайн."}else{SyncWorker.schedule(context);SyncWorker.now(context)}}
    suspend fun clearPartial()=withContext(Dispatchers.IO){mutex.withLock{mediaDir.listFiles()?.filter{it.extension in setOf("part","sha")}?.forEach{it.delete()}}}

    fun remove(id: String) {
        if (id == playingId) return
        media(id).delete(); File(mediaDir, "$id.part").delete(); File(mediaDir, "$id.part.sha").delete()
        db.execSQL("UPDATE videos SET downloaded=0,watched=1,updated=? WHERE id=?", arrayOf<Any>(System.currentTimeMillis()/1000.0, id)); reload()
        var cache=thumbs.listFiles()?.sumOf{it.length()}?:0
        videos.value.filter{!it.downloaded}.sortedBy{it.created}.forEach{old->if(cache>64L*1024*1024){val file=thumb(old.id);val length=file.length();if(file.delete())cache-=length}}
    }

    fun requestDownload(id: String) {
        db.execSQL("UPDATE videos SET watched=0,position=0,error='',updated=? WHERE id=?", arrayOf<Any>(System.currentTimeMillis()/1000.0, id)); reload()
    }

    fun clearWatched(automatic:Boolean=false) {
        val cutoff=System.currentTimeMillis()/1000.0-prefs.getInt("keep_watched_days",0)*86400
        videos.value.filter { it.downloaded && it.watched && !it.pinned && it.id != playingId && (!automatic||it.updated<=cutoff) && (!prefs.getBoolean("protect_saved",true)||!it.later&&it.playlist.isEmpty()) }.forEach { remove(it.id) }
    }

    private fun validateAddress(raw: String): String {
        val value = raw.trim().trimEnd('/').let { if ("://" in it) it else "http://$it" }
        var u = URI(value)
        if (u.port == -1 && u.host != null) u = URI(u.scheme, u.userInfo, u.host, 9473, u.path, u.query, u.fragment)
        require(u.scheme == "http" && u.host != null && u.userInfo == null && (u.path.isNullOrEmpty() || u.path == "/") && u.query == null && u.fragment == null) { "Введи локальный адрес с ПК: http://192.168.…:9473" }
        require(InetAddress.getAllByName(u.host).all { it.isSiteLocalAddress || it.isLoopbackAddress || it.isLinkLocalAddress }) { "Нужен локальный адрес компьютера в домашней сети" }
        require(u.port in 1..65535) { "Укажи порт из приложения на ПК, обычно 9473" }
        return u.toString().trimEnd('/')
    }

    private fun connection(base: String, path: String, auth: Boolean = true): HttpURLConnection {
        val c = localNetwork.open(URL(base + "/api/" + path))
        c.connectTimeout = 5000; c.readTimeout = 20000; c.instanceFollowRedirects = false
        if (auth) c.setRequestProperty("Authorization", "Bearer " + vault.read())
        return c
    }

    private fun json(base: String, path: String, body: JSONObject? = null, auth: Boolean = true): JSONObject {
        val c = connection(base, path, auth)
        try {
            if (body != null) {
                c.requestMethod = "POST"; c.doOutput = true; c.setRequestProperty("Content-Type", "application/json")
                c.outputStream.use { it.write(body.toString().toByteArray()) }
            }
            if (c.responseCode !in 200..299) {
                val detail = runCatching { JSONObject(c.errorStream.bufferedReader().use { it.readText().take(4096) }).optString("detail") }.getOrDefault("")
                error(detail.ifEmpty { "ПК ответил с ошибкой ${c.responseCode}" })
            }
            return JSONObject(c.inputStream.bufferedReader().use { it.readText() })
        } finally { c.disconnect() }
    }

    suspend fun pair(rawAddress: String, code: String) = withContext(Dispatchers.IO) {
        require(code.matches(Regex("[0-9]{6}"))) { "Введи шестизначный код с компьютера" }
        val candidate = if (rawAddress.isBlank()) {
            val found = localNetwork.discover()
            require(found.size == 1) { if (found.isEmpty()) "ПК не найден. Открой Запас на ПК и подключи телефон к той же домашней сети." else "Найдено несколько ПК. Выбери нужный компьютер." }
            found.single().address
        } else rawAddress
        val base = validateAddress(candidate)
        try {
        val health = json(base, "health", auth = false)
        require(health.optString("name") == "Запас" && health.optInt("version") == 1) { "По этому адресу работает другое приложение. Проверь адрес Запаса на ПК." }
        val response = json(base, "pair", JSONObject().put("code", code).put("name", android.os.Build.MODEL), false)
        vault.save(response.getString("token"))
        prefs.edit().putString("address", base).putString("server_id", response.optString("server_id", health.optString("server_id"))).commit()
        status.value = "Компьютер подключён"
        SyncWorker.schedule(context)
        } catch (e: kotlinx.coroutines.CancellationException) { throw e }
          catch (e: Exception) { throw IllegalStateException(localNetwork.error(e, base), e) }
    }

    suspend fun findComputers() = localNetwork.discover()
    fun connectionError(e: Exception) = localNetwork.error(e, address)

    private suspend fun reachableAddress(): String {
        val saved = validateAddress(address)
        try {
            val health = json(saved, "health", auth=false)
            val expected = prefs.getString("server_id", "").orEmpty()
            if (health.optString("name") == "Запас" && (expected.isEmpty() || health.optString("server_id") == expected)) return saved
        } catch (e: java.io.IOException) { /* IP may have changed since the last home visit. */ }
        val id = prefs.getString("server_id", "").orEmpty()
        val match = if (id.isNotEmpty()) localNetwork.discover().find { it.serverId == id } else null
        if (match != null) {
            prefs.edit().putString("address", match.address).commit()
            return match.address
        }
        throw java.net.ConnectException("Домашний ПК недоступен")
    }

    fun disconnect() { SyncWorker.cancel(context); vault.clear(); prefs.edit().remove("address").apply(); status.value = "Компьютер отключён. Видео остаются с тобой." }

    private fun onWifi(): Boolean {
        return localNetwork.network() != null
    }

    suspend fun sync(report: suspend (String) -> Unit = {}) = mutex.withLock {
        withContext(Dispatchers.IO) {
            if (!paired) { status.value = "Подключи компьютер в настройках"; return@withContext }
            if (phonePaused) { status.value="Загрузки на паузе. Видео доступны офлайн.";return@withContext }
            if (!onWifi()) { status.value = "Ждём домашний Wi‑Fi. Скачанные видео доступны."; return@withContext }
            syncing.value = true
            try {
                val base = reachableAddress()
                status.value = "Проверяем медиатеку на компьютере…"
                val quality = if (prefs.getBoolean("pending_quality", false)) {
                    json(base, "preferences", JSONObject().put("profile", prefs.getString("profile", "balanced")).put("codec", prefs.getString("codec", "h264")).put("encoding_mode",prefs.getString("encoding_mode","smart")))
                } else json(base, "preferences")
                prefs.edit().putString("profile", quality.getString("profile")).putString("codec", quality.getString("codec")).putString("encoding_mode",quality.optString("encoding_mode","smart")).putBoolean("pending_quality", false).apply()
                // Batch history so years of watched items do not exceed the server's request limit.
                for (batch in videos.value.chunked(500).ifEmpty { listOf(emptyList()) }) {
                    val items = JSONArray()
                    batch.forEach { items.put(JSONObject().put("video_id", it.id).put("position", it.position / 1000.0).put("watched", it.watched).put("updated", it.updated)) }
                    json(base, "progress", JSONObject().put("items", items).put("free_bytes", mediaDir.usableSpace).put("count", videos.value.count { it.downloaded }))
                }
                val manifest = json(base, "manifest")
                if(manifest.optBoolean("transfers_paused")){status.value="Передача на паузе на ПК. Уже скачанные видео доступны.";return@withContext}
                require(manifest.getInt("version") == 1) { "Обнови приложения: версия протокола изменилась" }
                val remote = manifest.getJSONArray("videos")
                require(remote.length() <= 1000) { "Медиатека слишком большая" }
                val remoteIds = mutableSetOf<String>()
                db.beginTransaction()
                try {
                    for (i in 0 until remote.length()) {
                        val v = remote.getJSONObject(i); val id = v.getString("id")
                        require(id.matches(Regex("[a-f0-9]{24}")) && v.getString("sha256").matches(Regex("[a-f0-9]{64}"))) { "Некорректный манифест" }
                        require(v.getLong("bytes") in 1..64L*1024*1024*1024) { "Некорректный размер видео" }
                        remoteIds += id
                        val cv = ContentValues().apply {
                            put("id", id); put("title", v.getString("title").take(500)); put("channel", v.optString("channel").take(300)); put("duration", v.getDouble("duration"))
                            put("bytes", v.getLong("bytes")); put("sha", v.getString("sha256")); put("height", v.getInt("height")); put("codec", v.getString("codec")); put("created", v.getDouble("created")); put("pinned", v.optInt("pinned"))
                        }
                        // Existing local watch progress and pins always survive a metadata refresh.
                        if (db.insertWithOnConflict("videos", null, cv, SQLiteDatabase.CONFLICT_IGNORE) == -1L) {
                            cv.remove("pinned")
                            val existing = videos.value.find { it.id == id }
                            if (existing == null || !existing.downloaded || existing.sha == v.getString("sha256")) db.update("videos", cv, "id=?", arrayOf(id))
                        }
                    }
                    db.setTransactionSuccessful()
                } finally { db.endTransaction() }
                reload()
                if (prefs.getBoolean("delete_watched", true)) clearWatched(automatic=true)
                val limits=limits()
                val countLimit = limits.maxCount
                var ready = videos.value.count { it.downloaded }
                var downloaded = 0
                var failed = 0
                var transferred=0L
                val sessionLimit=prefs.getInt("session_mb",0).toLong()*1024*1024
                val candidates = videos.value.filter { !it.downloaded && !it.watched && it.id in remoteIds }.let { list -> when(prefs.getString("download_order","small")){"new"->list.sortedByDescending{it.created};"short"->list.sortedBy{it.duration};"later"->list.sortedByDescending{it.later};else->list.sortedBy{it.bytes}} }
                for (v in candidates) {
                    currentCoroutineContext().ensureActive()
                    if(phonePaused)break
                    val usage=space();val partBytes=File(mediaDir,"${v.id}.part").length()
                    val reason=limits.reason(v,ready,usage.used,usage.free,partBytes) ?: if(sessionLimit>0&&transferred+v.bytes-partBytes>sessionLimit)"Лимит передачи за один сеанс" else null
                    if(reason!=null){db.update("videos",ContentValues().apply{put("error",reason)},"id=?",arrayOf(v.id));continue}
                    try {
                    status.value = "Скачиваем: ${v.title}"
                    report("${ready + 1} из $countLimit · ${v.title}")
                    transfer.value = 0f
                    // Cover is fetched before video becomes visible as ready, so offline cards are complete.
                    val tc = connection(base, "thumb/${v.id}")
                    try {
                        check(tc.responseCode == 200) { "Не удалось получить обложку" }
                        val tmp = File(thumbs, "${v.id}.part")
                        tc.inputStream.use { input -> FileOutputStream(tmp).use { out ->
                            val b=ByteArray(32768); var total=0
                            while (true) { val n=input.read(b); if(n<0) break; total+=n; check(total<=4*1024*1024) { "Обложка слишком большая" }; out.write(b,0,n) }
                        } }
                        check(tmp.renameTo(thumb(v.id))) { "Не удалось сохранить обложку" }
                    } finally { tc.disconnect() }
                    download(base, v)
                    db.execSQL("UPDATE videos SET downloaded=1,error='' WHERE id=?", arrayOf(v.id))
                    ready++; transferred+=(v.bytes-partBytes).coerceAtLeast(0); downloaded++; reload()
                    } catch (e: kotlinx.coroutines.CancellationException) {
                        throw e
                    } catch (e: java.io.IOException) {
                        // A lost network or full disk affects the whole transfer; keep the partial file.
                        throw e
                    } catch (e: Exception) {
                        failed++
                        db.update("videos",ContentValues().apply{put("error",e.message?.take(180)?:"Не удалось получить видео")},"id=?",arrayOf(v.id))
                        reload()
                    }
                }
                prefs.edit().putLong("last_sync", System.currentTimeMillis()).apply()
                json(base, "progress", JSONObject().put("items", JSONArray()).put("free_bytes", mediaDir.usableSpace).put("count", ready))
                status.value = when {
                    failed > 0 -> "$ready видео готовы. $failed не скачались — их можно повторить в «Загрузках»."
                    candidates.size > downloaded -> "$ready видео готовы. Остальные ждут места в лимите."
                    manifest.optBoolean("paused") -> "$ready видео готовы. Подготовка новых на ПК приостановлена."
                    manifest.optInt("pending")>0 -> "$ready видео готовы. ПК готовит ещё ${manifest.optInt("pending")}. Подхватим автоматически."
                    ready == 0 -> "На ПК пока нет новых готовых видео"
                    else -> "$ready видео с тобой. Можно без интернета."
                }
            } finally { syncing.value = false; transfer.value = 0f }
        }
    }

    private suspend fun download(base: String, v: Video) {
        val part = File(mediaDir, "${v.id}.part"); val version = File(mediaDir, "${v.id}.part.sha")
        if (!version.exists() || version.readText() != v.sha || part.length() > v.bytes) { part.delete(); version.writeText(v.sha) }
        if (part.length() < v.bytes) {
            val offset = part.length()
            val c = connection(base, "media/${v.id}")
            if (offset > 0) c.setRequestProperty("Range", "bytes=$offset-")
            try {
                val response = c.responseCode
                check(response == 200 || response == 206) { "Не удалось скачать видео ($response)" }
                val append = response == 206 && offset > 0
                if (response == 206) check(c.getHeaderField("Content-Range")?.startsWith("bytes $offset-") == true) { "ПК ответил неверным диапазоном файла" }
                var total = if (append) offset else 0L
                c.inputStream.use { input -> FileOutputStream(part, append).use { out ->
                    val buffer = ByteArray(128*1024)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        check(!phonePaused){"Загрузки приостановлены"}
                        check(mediaDir.usableSpace >= prefs.getInt("reserve_mb",1024).toLong()*1024*1024){"Остановлено, чтобы сохранить свободное место телефона"}
                        val n = input.read(buffer); if (n < 0) break
                        total += n; check(total <= v.bytes) { "Размер видео изменился. Повтори синхронизацию." }
                        out.write(buffer, 0, n); transfer.value = total.toFloat() / v.bytes
                    }
                    out.fd.sync()
                } }
            } finally { c.disconnect() }
        }
        check(part.length() == v.bytes) { "Передача прервана. Продолжим при следующем подключении." }
        val digest = MessageDigest.getInstance("SHA-256")
        part.inputStream().use { input ->
            val buffer=ByteArray(256*1024)
            while(true) { currentCoroutineContext().ensureActive(); val n=input.read(buffer); if(n<0) break; digest.update(buffer,0,n) }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        if (hash != v.sha) { part.delete(); version.delete(); error("Проверка файла не прошла. Скачаем заново при следующей синхронизации.") }
        check(part.renameTo(media(v.id))) { "Не удалось сохранить видео. Проверь свободное место." }
        version.delete()
    }
}

class ZapasApplication : Application() {
    val repository by lazy { Repository(this) }
    override fun onCreate() { super.onCreate(); if (repository.paired) SyncWorker.schedule(this) }
}

fun Context.repo(): Repository = (applicationContext as ZapasApplication).repository
