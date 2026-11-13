package app.zapas

import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.*

class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var lastSavedId: String? = null
    private var lastSavedPosition = -1L
    override fun onCreate() {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build().apply {
            setPlaybackSpeed(repo().prefs.getFloat("playback_speed",1f))
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true)
            setHandleAudioBecomingNoisy(true)
        }
        session = MediaSession.Builder(this, player).build()
        player.addListener(object : Player.Listener {
            override fun onMediaItemTransition(item: androidx.media3.common.MediaItem?, reason: Int) {
                repo().playingId = item?.mediaId
            }
            @androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int) {
                if (reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION) {
                    oldPosition.mediaItem?.mediaId?.let { repo().progress(it, 0, true) }
                }
            }
            override fun onPlaybackStateChanged(state: Int) {
                if (state == Player.STATE_ENDED) save(true)
            }
            override fun onIsPlayingChanged(isPlaying: Boolean) { if (!isPlaying) save() }
        })
        scope.launch { while (isActive) {
            delay(1000)
            val deadline=repo().sleepDeadline.value
            if(deadline>0&&System.currentTimeMillis()>=deadline){player.pause();repo().sleepDeadline.value=0}
            save()
        } }
    }
    private fun save(ended: Boolean = false) {
        val p = session?.player ?: return
        val id = p.currentMediaItem?.mediaId ?: return
        if(!ended&&id==lastSavedId&&kotlin.math.abs(p.currentPosition-lastSavedPosition)<3000)return
        lastSavedId=id;lastSavedPosition=p.currentPosition
        repo().progress(id, if (ended) 0 else p.currentPosition, ended)
    }
    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session
    override fun onTaskRemoved(rootIntent: android.content.Intent?) {
        save()
        if (session?.player?.isPlaying != true) stopSelf()
    }
    override fun onDestroy() {
        save(); repo().playingId = null; scope.cancel()
        session?.run { player.release(); release() }; session = null
        super.onDestroy()
    }
}
