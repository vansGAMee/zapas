@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.zapas

import android.app.PictureInPictureParams
import android.graphics.Rect
import android.os.Build
import android.util.Rational
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.background
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView

@androidx.annotation.OptIn(markerClass=[androidx.media3.common.util.UnstableApi::class])
@Composable
internal fun WatchScreen(v:Video,player:MediaController?,repo:Repository,error:String,onBack:()->Unit,onPlay:(Video)->Unit,onMore:(Video)->Unit) {
    val activity=LocalActivity.current as? MainActivity
    val pip=activity?.pip?.value==true
    val all by repo.videos.collectAsStateWithLifecycle()
    val sleep by repo.sleepDeadline.collectAsStateWithLifecycle()
    var settings by remember{mutableStateOf(false)}
    var full by rememberSaveable{mutableStateOf(false)}
    var locked by rememberSaveable{mutableStateOf(false)}
    var playing by remember(player){mutableStateOf(player?.isPlaying==true)}
    var controlsVisible by remember(player){mutableStateOf(true)}
    var speed by remember(player){mutableFloatStateOf(player?.playbackParameters?.speed?:1f)}
    var repeat by remember(player){mutableStateOf(player?.repeatMode==Player.REPEAT_MODE_ONE)}
    var fit by remember{mutableStateOf(repo.prefs.getString("video_fit","fit"))}
    DisposableEffect(player){val listener=object:Player.Listener{override fun onIsPlayingChanged(isPlaying:Boolean){playing=isPlaying}};player?.addListener(listener);onDispose{player?.removeListener(listener);activity?.updatePipParams(false)}}
    SideEffect {activity?.updatePipParams(playing&&repo.prefs.getBoolean("pip",true))}
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val mode=repo.prefs.getString("screen_mode","auto")
        val wide=maxWidth>=840.dp
        val fullscreen=full||mode=="full"||mode=="auto"&&maxWidth>maxHeight&&!wide||pip
        DisposableEffect(fullscreen){val bars=activity?.window?.let{WindowCompat.getInsetsController(it,it.decorView)};if(fullscreen&&!pip){bars?.systemBarsBehavior=WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE;bars?.hide(WindowInsetsCompat.Type.systemBars())};onDispose{bars?.show(WindowInsetsCompat.Type.systemBars())}}
        BackHandler(full||locked){if(locked)locked=false else full=false}
        val video:@Composable (Modifier)->Unit={ modifier ->
            Box(modifier.background(Color.Black)) {
                AndroidView(factory={ctx->PlayerView(ctx).apply{
                    this.player=player;setShowNextButton(true);setShowPreviousButton(true);setShowRewindButton(true);setShowFastForwardButton(true);setShowSubtitleButton(false)
                    setControllerVisibilityListener(PlayerView.ControllerVisibilityListener{visibility->controlsVisible=visibility==android.view.View.VISIBLE})
                    findViewById<android.view.View>(androidx.media3.ui.R.id.exo_settings)?.visibility=android.view.View.GONE
                    addOnLayoutChangeListener{view,_,_,_,_,_,_,_,_->if(activity?.isInPictureInPictureMode==false){val bounds=Rect();view.getGlobalVisibleRect(bounds);activity.updatePipParams(activity.pipEligible,bounds)}}
                }},update={view->view.player=player;view.keepScreenOn=playing;view.useController=!locked&&!pip;view.resizeMode=if(fit=="zoom")AspectRatioFrameLayout.RESIZE_MODE_ZOOM else AspectRatioFrameLayout.RESIZE_MODE_FIT},onRelease={it.player=null},modifier=Modifier.fillMaxSize())
                if(!pip&&(controlsVisible||locked))Row(Modifier.align(Alignment.TopEnd).background(Color.Black.copy(alpha=.35f)),verticalAlignment=Alignment.CenterVertically){
                    if(!locked){IconButton(onClick={full=!full}){Icon(if(fullscreen)Icons.Outlined.FullscreenExit else Icons.Outlined.Fullscreen,"Полный экран",tint=Color.White,modifier=Modifier.size(24.dp))};IconButton(onClick={settings=true}){Icon(Icons.Rounded.Settings,"Настройки плеера",tint=Color.White,modifier=Modifier.size(24.dp))}}
                    IconButton(onClick={locked=!locked}){Icon(if(locked)Icons.Outlined.Lock else Icons.Outlined.LockOpen,if(locked)"Разблокировать управление" else "Заблокировать управление",tint=Color.White,modifier=Modifier.size(24.dp))}
                }
                if(fullscreen&&!locked&&!pip&&controlsVisible)IconButton(onClick={if(full)full=false else onBack()},modifier=Modifier.align(Alignment.TopStart)){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Свернуть плеер",tint=Color.White)}
                if(!locked&&!pip&&controlsVisible)Row(Modifier.align(Alignment.BottomCenter).padding(bottom=64.dp),horizontalArrangement=Arrangement.spacedBy(16.dp)){
                    val step=repo.prefs.getInt("seek_seconds",10)*1000L
                    FilledTonalIconButton(onClick={player?.seekTo(((player.currentPosition)-step).coerceAtLeast(0))}){Icon(Icons.Outlined.Replay,"Назад ${step/1000} секунд")}
                    FilledTonalIconButton(onClick={player?.seekTo((player.currentPosition+step).coerceAtMost(player.duration.coerceAtLeast(0)))}){Icon(Icons.Outlined.Forward10,"Вперёд ${step/1000} секунд")}
                }
            }
        }
        val description:@Composable ()->Unit={
            Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(v.title,style=MaterialTheme.typography.titleLarge,fontWeight=FontWeight.SemiBold)
                Text("${v.channel} · ${v.height}p · ${size(v.bytes)} · ${duration(v.duration)}",color=MaterialTheme.colorScheme.onSurfaceVariant)
                Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected=v.liked,onClick={repo.like(v.id)},label={Text("Нравится")},leadingIcon={Icon(Icons.Outlined.ThumbUp,null,Modifier.size(18.dp))})
                    FilterChip(selected=v.later,onClick={repo.later(v.id)},label={Text("Позже")},leadingIcon={Icon(Icons.Outlined.WatchLater,null,Modifier.size(18.dp))})
                    FilterChip(selected=v.pinned,onClick={repo.pin(v.id)},label={Text("Закрепить")},leadingIcon={Icon(Icons.Outlined.PushPin,null,Modifier.size(18.dp))})
                    AssistChip(onClick={onMore(v)},label={Text("Ещё")})
                }
                if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error)
                if(sleep>0)Text("Таймер сна включён",color=MaterialTheme.colorScheme.primary)
            }
        }
        val next=all.filter{it.downloaded&&it.id!=v.id}
        if(fullscreen)video(Modifier.fillMaxSize())
        else Column {
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){IconButton(onClick=onBack){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Свернуть плеер")};Text("Смотрим из запаса",style=MaterialTheme.typography.labelLarge);Spacer(Modifier.weight(1f));IconButton(onClick={activity?.enterPip()}){Icon(Icons.Outlined.PictureInPictureAlt,"Картинка в картинке")}}
            if(wide)Row(Modifier.weight(1f),horizontalArrangement=Arrangement.spacedBy(20.dp)){
                LazyColumn(Modifier.weight(1.8f)){item{video(Modifier.fillMaxWidth().aspectRatio(16f/9))};item{description()}}
                LazyColumn(Modifier.weight(1f).padding(end=16.dp),verticalArrangement=Arrangement.spacedBy(18.dp)){item{Text("Следующее",style=MaterialTheme.typography.titleMedium)};items(next,key={it.id}){item->VideoCard(item,repo,{onPlay(item)},{onMore(item)})}}
            } else LazyColumn(verticalArrangement=Arrangement.spacedBy(16.dp),contentPadding=PaddingValues(bottom=24.dp)){
                item{video(Modifier.fillMaxWidth().aspectRatio(16f/9))};item{description();Text("Следующее в запасе",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(horizontal=20.dp))}
                items(next,key={it.id}){item->Box(Modifier.padding(horizontal=16.dp)){VideoCard(item,repo,{onPlay(item)},{onMore(item)})}}
        }
    }
    }
    if(settings)ModalBottomSheet(onDismissRequest={settings=false}){
        Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(start=24.dp,end=24.dp,bottom=32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)){
            Text("Настройки плеера",style=MaterialTheme.typography.titleLarge);Text("Скорость · $speed×")
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(.5f,.75f,1f,1.25f,1.5f,1.75f,2f).forEach{n->FilterChip(selected=speed==n,onClick={speed=n;player?.setPlaybackSpeed(n);repo.prefs.edit().putFloat("playback_speed",n).apply()},label={Text("$n×")})}}
            ListItem(headlineContent={Text("Повторять этот ролик")},trailingContent={Switch(checked=repeat,onCheckedChange={repeat=it;player?.repeatMode=if(it)Player.REPEAT_MODE_ONE else Player.REPEAT_MODE_OFF})})
            ListItem(headlineContent={Text("Заполнить экран")},supportingContent={Text("Края видео могут обрезаться")},trailingContent={Switch(checked=fit=="zoom",onCheckedChange={fit=if(it)"zoom" else "fit";repo.prefs.edit().putString("video_fit",fit).apply()})})
            Text("Таймер сна")
            Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(0,15,30,60,90).forEach{minutes->AssistChip(onClick={repo.sleepDeadline.value=if(minutes==0)0 else System.currentTimeMillis()+minutes*60_000L;settings=false},label={Text(if(minutes==0)"Выключить" else "$minutes мин")})}}
            Text("${v.height}p · ${v.codec.uppercase()} · ${size(v.bytes)}. Качество этого файла уже сохранено. Профиль новых загрузок меняется в настройках приложения.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}
