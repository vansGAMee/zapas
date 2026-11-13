@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
package app.zapas

import android.Manifest
import android.content.ComponentName
import android.os.Build
import android.os.Bundle
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.grid.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.PlaylistPlay
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.ui.platform.LocalConfiguration
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.delay
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import androidx.media3.ui.PlayerView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.DateFormat
import java.util.Date

private val DarkColors = darkColorScheme(
    primary = Color(0xFFFF8B9A), onPrimary = Color(0xFF5F1024), primaryContainer = Color(0xFF44202A), onPrimaryContainer = Color(0xFFFFD9DE),
    secondary = Color(0xFFE2BDC2), secondaryContainer = Color(0xFF3E3033), onSecondaryContainer = Color(0xFFFFD9DE),
    background = Color(0xFF0F0F0F), surface = Color(0xFF0F0F0F), surfaceContainer = Color(0xFF1B1B1B), surfaceContainerHigh = Color(0xFF272727),
    surfaceContainerHighest = Color(0xFF343434), onSurface = Color(0xFFF3F3F3), onSurfaceVariant = Color(0xFFB7B7B7), outline = Color(0xFF777777), outlineVariant = Color(0xFF363636)
)
private val LightColors = lightColorScheme(primary=Color(0xFFB3203C),onPrimary=Color.White,primaryContainer=Color(0xFFFFD9DE),onPrimaryContainer=Color(0xFF4A0012),secondaryContainer=Color(0xFFF1DDE0),surface=Color(0xFFFFF8F7),background=Color(0xFFFFF8F7))
internal fun size(n: Long) = if (n >= 1024L*1024*1024) "%.1f ГБ".format(n.toDouble()/1024/1024/1024) else if(n>=1024*1024) "${n/1024/1024} МБ" else "${n/1024} КБ"
internal fun duration(n: Double): String { val s=n.toInt(); return if(s>=3600) "%d:%02d:%02d".format(s/3600,s/60%60,s%60) else "%d:%02d".format(s/60,s%60) }

class MainActivity : ComponentActivity() {
    val pip = mutableStateOf(false)
    var pipEligible=false
    private var pipSourceBounds: android.graphics.Rect? = null
    private val incoming = mutableStateOf<Pair<String,String>?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState); enableEdgeToEdge(); readLink(intent?.data)
        setContent { ZapasApp(incoming.value) { incoming.value=null } }
    }
    override fun onPictureInPictureModeChanged(inPictureInPictureMode:Boolean,newConfig:android.content.res.Configuration){super.onPictureInPictureModeChanged(inPictureInPictureMode,newConfig);pip.value=inPictureInPictureMode}
    fun updatePipParams(enabled:Boolean,bounds:android.graphics.Rect?=null){
        pipEligible=enabled
        if(bounds!=null&&!bounds.isEmpty)pipSourceBounds=android.graphics.Rect(bounds)
        val builder=android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16,9))
        pipSourceBounds?.let{builder.setSourceRectHint(it)}
        if(Build.VERSION.SDK_INT>=31)builder.setAutoEnterEnabled(enabled)
        runCatching{setPictureInPictureParams(builder.build())}
    }
    fun enterPip(){if(pipEligible && packageManager.hasSystemFeature(android.content.pm.PackageManager.FEATURE_PICTURE_IN_PICTURE))runCatching{enterPictureInPictureMode(android.app.PictureInPictureParams.Builder().setAspectRatio(android.util.Rational(16,9)).build())}}
    override fun onUserLeaveHint(){super.onUserLeaveHint();if(!isInPictureInPictureMode)enterPip()}
    override fun onNewIntent(intent: android.content.Intent) { super.onNewIntent(intent); readLink(intent.data) }
    private fun readLink(uri: Uri?) { if(uri?.scheme=="zapas"&&uri.host=="pair") incoming.value=Pair(uri.getQueryParameter("address").orEmpty(),uri.getQueryParameter("code").orEmpty()) }
}

@Composable
private fun ZapasApp(incoming: Pair<String,String>?, consumeLink: ()->Unit) {
    val context=LocalContext.current; val repo=remember { context.repo() }; val videos by repo.videos.collectAsStateWithLifecycle()
    val status by repo.status.collectAsStateWithLifecycle(); val syncing by repo.syncing.collectAsStateWithLifecycle(); val transfer by repo.transfer.collectAsStateWithLifecycle()
    var page by rememberSaveable { mutableIntStateOf(0) }; var search by rememberSaveable { mutableStateOf("") }; var searchOpen by rememberSaveable { mutableStateOf(false) }
    var filter by rememberSaveable { mutableStateOf("Все") }; var showPair by rememberSaveable { mutableStateOf(false) }; var selected by remember { mutableStateOf<Video?>(null) }
    var playlistVideo by remember { mutableStateOf<Video?>(null) }; var playlistName by remember { mutableStateOf("") }
    var delete by remember { mutableStateOf<Video?>(null) }; var disconnect by remember { mutableStateOf(false) }; var clear by remember { mutableStateOf(false) }
    var dark by rememberSaveable { mutableStateOf(repo.prefs.getBoolean("dark",true)) }
    var controller by remember { mutableStateOf<MediaController?>(null) }; var current by remember { mutableStateOf<String?>(null) }; var mini by rememberSaveable { mutableStateOf(false) }
    var pendingPlay by remember { mutableStateOf<String?>(null) }
    var connectionError by remember { mutableStateOf("") }
    var error by remember { mutableStateOf("") }; val snackbar=remember { SnackbarHostState() }; val scope=rememberCoroutineScope()
    val notification=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    fun sync() { if(!repo.paired) showPair=true else { if(Build.VERSION.SDK_INT>=33) notification.launch(Manifest.permission.POST_NOTIFICATIONS); SyncWorker.now(context) } }
    LaunchedEffect(incoming) { if(incoming!=null) showPair=true }
    DisposableEffect(Unit) {
        val future=MediaController.Builder(context, SessionToken(context,ComponentName(context,PlaybackService::class.java))).buildAsync()
        future.addListener({ runCatching { future.get() }.onSuccess { p ->
            controller=p; current=p.currentMediaItem?.mediaId
            p.addListener(object : Player.Listener {
                override fun onMediaItemTransition(item: MediaItem?, reason: Int) { current=item?.mediaId }
                override fun onPlayerError(e: androidx.media3.common.PlaybackException) { error="Не удалось открыть видео. Попробуй H.264 для следующих загрузок. ${e.errorCodeName}" }
            })
        }.onFailure { connectionError="Не удалось запустить плеер. Открой приложение заново." } },ContextCompat.getMainExecutor(context))
        onDispose { MediaController.releaseFuture(future) }
    }
    fun play(v: Video) {
        if(!v.downloaded){repo.requestDownload(v.id);sync();return}
        val p=controller ?: run { pendingPlay=v.id;return }
        val ordered=listOf(v)+videos.filter { it.downloaded&&it.id!=v.id&&!it.watched }
        val list=(if(repo.prefs.getBoolean("autoplay",true)) ordered else listOf(v)).map { item ->
            MediaItem.Builder().setMediaId(item.id).setUri(Uri.fromFile(repo.media(item.id)))
                .setMediaMetadata(MediaMetadata.Builder().setTitle(item.title).setArtist(item.channel).setArtworkUri(Uri.fromFile(repo.thumb(item.id))).build()).build()
        }
        p.setMediaItems(list,0,if(v.watched)0 else v.position); p.prepare(); p.play(); current=v.id; mini=false; error=""
    }
    LaunchedEffect(controller,pendingPlay){if(controller!=null&&pendingPlay!=null){val id=pendingPlay;pendingPlay=null;videos.find{it.id==id}?.let{play(it)}}}
    LaunchedEffect(connectionError){if(connectionError.isNotEmpty())snackbar.showSnackbar(connectionError)}
    val lifecycleOwner=LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner){lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED){while(true){if(repo.paired&&!repo.phonePaused&&repo.prefs.getBoolean("auto_sync",true))SyncWorker.now(context);delay(30_000)}}}
    val activity=LocalActivity.current as? MainActivity
    val pip=activity?.pip?.value==true
    val wide=LocalConfiguration.current.screenWidthDp>=600
    val active=videos.find { it.id==current }
    MaterialTheme(colorScheme=if(dark) DarkColors else LightColors) {
        SideEffect {
            activity?.window?.let { window ->
                androidx.core.view.WindowCompat.getInsetsController(window,window.decorView).apply {
                    isAppearanceLightStatusBars=!dark; isAppearanceLightNavigationBars=!dark
                }
            }
        }
        val colors=MaterialTheme.colorScheme
        BackHandler(current!=null&&!mini || page!=0 || searchOpen) { when {current!=null&&!mini -> mini=true; searchOpen -> {searchOpen=false; search=""}; else -> page=0} }
        Scaffold(containerColor=colors.surface,snackbarHost={SnackbarHost(snackbar)},topBar={
            if((active==null||mini)&&!pip) {
                if(searchOpen) TopAppBar(title={TextField(value=search,onValueChange={search=it},placeholder={Text("Название, канал, «короткие»…")},singleLine=true,modifier=Modifier.fillMaxWidth(),colors=TextFieldDefaults.colors(unfocusedContainerColor=Color.Transparent,focusedContainerColor=Color.Transparent))},navigationIcon={IconButton(onClick={searchOpen=false;search=""}){Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Закрыть поиск")}})
                else TopAppBar(title={Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)){Icon(Icons.Outlined.SmartDisplay,null,tint=Color(0xFFFF455D),modifier=Modifier.size(32.dp));Text("запас",fontWeight=FontWeight.Bold,fontSize=27.sp,letterSpacing=(-1).sp)}},actions={IconButton(onClick={page=0;searchOpen=true}){Icon(Icons.Outlined.Search,"Поиск")};IconButton(onClick={sync()}){Icon(if(syncing)Icons.Outlined.Sync else Icons.Outlined.Devices,"Синхронизировать с ПК")}})
            }
        },bottomBar={
            if((active==null||mini)&&!pip&&!wide) Column {
                if(active!=null) MiniPlayer(active,controller,onOpen={mini=false},onClose={controller?.stop();controller?.clearMediaItems();current=null})
                NavigationBar(containerColor=colors.surfaceContainer) {
                    listOf(Triple("Главная",Icons.Outlined.Home,0),Triple("Загрузки",Icons.Outlined.Download,1),Triple("Медиатека",Icons.AutoMirrored.Outlined.PlaylistPlay,2),Triple("Настройки",Icons.Rounded.Settings,3)).forEach { (t,i,n)->
                        NavigationBarItem(selected=page==n,onClick={page=n;filter="Все"},icon={Icon(i,null,Modifier.size(24.dp))},label={Text(t,maxLines=1,fontSize=11.sp)})
                    }
                }
            }
        }) { padding ->
            Row(Modifier.fillMaxSize().padding(padding)) {
                if(wide&&!pip&&(active==null||mini))NavigationRail(containerColor=colors.surface){listOf(Triple("Главная",Icons.Outlined.Home,0),Triple("Загрузки",Icons.Outlined.Download,1),Triple("Медиатека",Icons.AutoMirrored.Outlined.PlaylistPlay,2),Triple("Настройки",Icons.Rounded.Settings,3)).forEach{(label,image,index)->NavigationRailItem(selected=page==index,onClick={page=index;filter="Все"},icon={Icon(image,null,Modifier.size(24.dp))},label={Text(label)})}}
                Column(Modifier.weight(1f)) {
                if(wide&&active!=null&&mini)MiniPlayer(active,controller,{mini=false},{controller?.stop();controller?.clearMediaItems();current=null})
                Box(Modifier.weight(1f)) {
                if(active!=null&&(!mini||pip)) WatchScreen(active,controller,repo,error,onBack={mini=true},onPlay=::play,onMore={selected=it})
                else when(page) {
                    0,2 -> {
                        val ready=videos.filter { it.downloaded || page==2&&filter=="История"&&(it.watched||it.position>0) }
                        val shown=SmartSearch.find(ready,search).filter { when(filter){"Продолжить"->it.position>0&&!it.watched;"Закреплённые"->it.pinned;"Непросмотренные"->!it.watched;"История"->it.watched||it.position>0;"Понравившиеся"->it.liked;"Смотреть позже"->it.later;"Все"->true;else->it.playlist==filter} }
                        Feed(shown,ready.size,ready.sumOf { it.duration },filter,{filter=it},repo,status,syncing,page==2,search,onPlay=::play,onMore={selected=it},onSync=::sync,onSettings={page=3})
                    }
                    1 -> Downloads(videos,repo,status,syncing,transfer,onSync=::sync,onStop={repo.pauseDownloads(true)},onPlay=::play)
                    3 -> SettingsScreen(repo,dark,onDark={dark=it;repo.prefs.edit().putBoolean("dark",it).apply()},onPair={showPair=true},onSync=::sync,onDisconnect={disconnect=true},onClear={clear=true})
                }
                }
                }
            }
        }
        if(showPair) PairDialog(repo,incoming,onDismiss={showPair=false;consumeLink()},onPaired={showPair=false;consumeLink();sync();scope.launch{snackbar.showSnackbar("Компьютер подключён")}})
        selected?.let { v -> ModalBottomSheet(onDismissRequest={selected=null}) { Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom=24.dp)) {
            Text(v.title,style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(horizontal=24.dp,vertical=12.dp))
            ListItem(headlineContent={Text(if(v.pinned)"Открепить видео" else "Закрепить на телефоне")},supportingContent={Text("Закреплённое не удаляется автоматически")},leadingContent={Icon(Icons.Outlined.PushPin,null)},modifier=Modifier.clickable{repo.pin(v.id);selected=null})
            ListItem(headlineContent={Text(if(v.liked)"Убрать из понравившихся" else "Мне нравится")},leadingContent={Icon(Icons.Outlined.ThumbUp,null)},modifier=Modifier.clickable{repo.like(v.id);selected=null})
            ListItem(headlineContent={Text(if(v.later)"Убрать из «Смотреть позже»" else "Смотреть позже")},leadingContent={Icon(Icons.Outlined.WatchLater,null)},modifier=Modifier.clickable{repo.later(v.id);selected=null})
            ListItem(headlineContent={Text("Добавить в плейлист")},leadingContent={Icon(Icons.AutoMirrored.Outlined.PlaylistPlay,null)},modifier=Modifier.clickable{playlistVideo=v;playlistName=v.playlist;selected=null})
            ListItem(headlineContent={Text(if(v.watched)"Отметить непросмотренным" else "Отметить просмотренным")},leadingContent={Icon(Icons.Outlined.Done,null)},modifier=Modifier.clickable{repo.markWatched(v.id,!v.watched);selected=null})
            if(v.downloaded&&controller?.mediaItemCount?.let{it>0}==true)ListItem(headlineContent={Text("Смотреть следующим")},leadingContent={Icon(Icons.Outlined.SkipNext,null)},modifier=Modifier.clickable{controller?.let{p->p.addMediaItem(p.currentMediaItemIndex+1,MediaItem.Builder().setMediaId(v.id).setUri(Uri.fromFile(repo.media(v.id))).setMediaMetadata(MediaMetadata.Builder().setTitle(v.title).setArtist(v.channel).build()).build())};selected=null})
            if(v.downloaded)ListItem(headlineContent={Text("Поделиться файлом")},leadingContent={Icon(Icons.Outlined.Share,null)},modifier=Modifier.clickable{val uri=androidx.core.content.FileProvider.getUriForFile(context,context.packageName+".files",repo.media(v.id));val intent=android.content.Intent(android.content.Intent.ACTION_SEND).setType("video/mp4").putExtra(android.content.Intent.EXTRA_STREAM,uri).addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION);context.startActivity(android.content.Intent.createChooser(intent,"Поделиться видео"));selected=null})
            ListItem(headlineContent={Text("Удалить с телефона")},leadingContent={Icon(Icons.Outlined.DeleteOutline,null)},modifier=Modifier.clickable{selected=null;delete=v})
        } } }
        playlistVideo?.let { v->AlertDialog(onDismissRequest={playlistVideo=null},title={Text("Плейлист на телефоне")},text={OutlinedTextField(value=playlistName,onValueChange={playlistName=it.take(60)},label={Text("Название")},supportingText={Text("Одинаковое название объединяет видео. Пустое — убрать из плейлиста.")})},confirmButton={TextButton(onClick={repo.playlist(v.id,playlistName);playlistVideo=null}){Text("Сохранить")}},dismissButton={TextButton(onClick={playlistVideo=null}){Text("Отмена")}}) }
        delete?.let { v -> AlertDialog(onDismissRequest={delete=null},title={Text("Удалить видео?")},text={Text("«${v.title}» будет удалено с телефона и отмечено просмотренным. Копия на компьютере сохранится.")},confirmButton={TextButton(onClick={repo.remove(v.id);delete=null}){Text("Удалить")}},dismissButton={TextButton(onClick={delete=null}){Text("Отмена")}}) }
        if(disconnect) AlertDialog(onDismissRequest={disconnect=false},title={Text("Отключить компьютер?")},text={Text("Скачанные видео останутся доступны. Для пополнения понадобится новый код.")},confirmButton={TextButton(onClick={repo.disconnect();disconnect=false}){Text("Отключить")}},dismissButton={TextButton(onClick={disconnect=false}){Text("Отмена")}})
        if(clear) AlertDialog(onDismissRequest={clear=false},title={Text("Удалить просмотренное?")},text={Text("Закреплённые и текущий ролик останутся. Освободится ${size(videos.filter{it.downloaded&&it.watched&&!it.pinned&&it.id!=repo.playingId}.sumOf{it.bytes})}.")},confirmButton={TextButton(onClick={repo.clearWatched();clear=false}){Text("Очистить")}},dismissButton={TextButton(onClick={clear=false}){Text("Отмена")}})
    }
}

@Composable
internal fun LocalImage(file: File, modifier: Modifier=Modifier) {
    val bitmap by produceState<ImageBitmap?>(null,file.absolutePath,file.lastModified()) {
        value=withContext(Dispatchers.IO){runCatching{android.graphics.BitmapFactory.decodeFile(file.absolutePath)?.asImageBitmap()}.getOrNull()}
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceContainerHigh),contentAlignment=Alignment.Center) {
        if(bitmap!=null) Image(bitmap!!,null,Modifier.fillMaxSize(),contentScale=ContentScale.Crop) else Icon(Icons.Outlined.SmartDisplay,null,tint=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun VideoCard(v: Video,repo: Repository,onPlay:()->Unit,onMore:()->Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().aspectRatio(16f/9).clip(RoundedCornerShape(12.dp)).clickable(onClickLabel="Смотреть ${v.title}",onClick=onPlay)) {
            LocalImage(repo.thumb(v.id),Modifier.fillMaxSize())
            Surface(color=Color.Black.copy(alpha=.8f),shape=RoundedCornerShape(4.dp),modifier=Modifier.align(Alignment.BottomEnd).padding(8.dp)){Text(duration(v.duration),color=Color.White,fontSize=12.sp,modifier=Modifier.padding(horizontal=5.dp,vertical=2.dp))}
            if(v.position>0&&!v.watched) LinearProgressIndicator(progress={ (v.position/(v.duration*1000)).toFloat().coerceIn(0f,1f) },modifier=Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(3.dp),color=Color(0xFFFF455D),trackColor=Color.White.copy(alpha=.3f))
        }
        Row(Modifier.fillMaxWidth().padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Surface(shape=CircleShape,color=MaterialTheme.colorScheme.surfaceContainerHigh,modifier=Modifier.padding(top=2.dp).size(34.dp)){Box(contentAlignment=Alignment.Center){Text(v.channel.take(1),fontWeight=FontWeight.Medium)}}
            Column(Modifier.weight(1f).clickable(onClick=onPlay)){Text(v.title,fontWeight=FontWeight.Medium,fontSize=15.sp,lineHeight=21.sp,maxLines=2,overflow=TextOverflow.Ellipsis);Text(v.channel,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(top=5.dp));Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)){Icon(Icons.Outlined.CheckCircle,null,Modifier.size(13.dp),tint=MaterialTheme.colorScheme.primary);Text("${v.height}p · ${size(v.bytes)}${if(v.pinned)" · закреплено" else ""}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)}}
            IconButton(onClick=onMore,modifier=Modifier.size(48.dp)){Icon(Icons.Outlined.MoreVert,"Действия с ${v.title}")}
        }
    }
}

@Composable
private fun Feed(videos: List<Video>,count: Int,time: Double,filter: String,onFilter:(String)->Unit,repo: Repository,status: String,syncing: Boolean,library: Boolean,search: String,onPlay:(Video)->Unit,onMore:(Video)->Unit,onSync:()->Unit,onSettings:()->Unit) {
    val allLibrary by repo.videos.collectAsStateWithLifecycle()
    LazyVerticalGrid(columns=GridCells.Adaptive(300.dp),contentPadding=PaddingValues(start=16.dp,end=16.dp,bottom=24.dp),horizontalArrangement=Arrangement.spacedBy(20.dp),verticalArrangement=Arrangement.spacedBy(26.dp)) {
        item(span={GridItemSpan(maxLineSpan)}) { Column {
            Text(if(search.isNotBlank())"Результаты поиска" else if(library)"Твоя медиатека" else "Видео с собой",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold,modifier=Modifier.padding(top=12.dp,bottom=8.dp))
            Text(if(search.isNotBlank())"Найдено: ${videos.size} · поиск работает без интернета" else if(count>0)"$count видео · ${duration(time)} просмотра без сети" else "Твоя лента, которой не нужен интернет",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyMedium)
            if(count>0||syncing) Surface(color=MaterialTheme.colorScheme.surfaceContainer,shape=RoundedCornerShape(16.dp),modifier=Modifier.fillMaxWidth().padding(top=22.dp).clickable(onClick=onSync)) { Row(Modifier.padding(16.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) { Icon(if(syncing)Icons.Outlined.Sync else Icons.Outlined.OfflinePin,null,tint=MaterialTheme.colorScheme.primary);Column(Modifier.weight(1f)){Text(if(syncing)"Пополняем запас" else "Можно без интернета",fontWeight=FontWeight.Medium);Text(status,color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodySmall,modifier=Modifier.padding(top=4.dp))} } }
            if(count>0) Row(Modifier.padding(top=16.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                // Three compact filters fit narrow windows; the library has its own saved filter.
                (if(library) listOf("Все","Смотреть позже","Понравившиеся","История","Закреплённые")+allLibrary.map{it.playlist}.filter{it.isNotBlank()}.distinct() else listOf("Все","Продолжить","Непросмотренные")).forEach { t->FilterChip(selected=filter==t,onClick={onFilter(t)},label={Text(t,fontSize=12.sp)}) }
            }
        } }
        if(videos.isEmpty()) item(span={GridItemSpan(maxLineSpan)}) { Column(Modifier.fillMaxWidth().padding(vertical=40.dp,horizontal=16.dp),horizontalAlignment=Alignment.CenterHorizontally) {
            Icon(if(search.isNotEmpty())Icons.Outlined.SearchOff else Icons.Outlined.OfflinePin,null,Modifier.size(64.dp),tint=MaterialTheme.colorScheme.primary)
            Text(if(search.isNotEmpty())"Ничего не нашлось" else if(count>0||library)"Здесь пока пусто" else "Любимые видео уже в пути",style=MaterialTheme.typography.headlineSmall,modifier=Modifier.padding(top=24.dp),fontWeight=FontWeight.SemiBold)
            Text(if(search.isNotEmpty())"Попробуй другое название или имя канала." else if(count>0||library)"Продолжай смотреть или закрепи видео — они появятся здесь." else "Подключи свой YouTube на компьютере один раз. Запас сам подготовит рекомендации и передаст их на телефон дома.",color=MaterialTheme.colorScheme.onSurfaceVariant,style=MaterialTheme.typography.bodyLarge,modifier=Modifier.padding(vertical=16.dp))
            if(count==0&&!library) Button(onClick=onSync,modifier=Modifier.heightIn(min=48.dp)){Icon(Icons.Outlined.Devices,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text(if(repo.paired)"Синхронизировать" else "Подключить компьютер")}
        } }
        items(videos,key={it.id}) { v->VideoCard(v,repo,{onPlay(v)},{onMore(v)}) }
    }
}

@Composable
private fun Downloads(videos: List<Video>,repo: Repository,status:String,syncing:Boolean,transfer:Float,onSync:()->Unit,onStop:()->Unit,onPlay:(Video)->Unit) {
    LazyColumn(contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item { Text("Загрузки",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold);Text(status,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=16.dp));if(syncing)LinearProgressIndicator(progress={transfer},modifier=Modifier.fillMaxWidth());Row(Modifier.padding(vertical=12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)){Button(onClick={if(repo.phonePaused)repo.pauseDownloads(false)else onSync()},enabled=!syncing){Text(if(repo.phonePaused)"Возобновить загрузки" else "Пополнить запас")};if(syncing)OutlinedButton(onClick=onStop){Text("Остановить")}};Text("${videos.count{it.downloaded}} видео на телефоне · ${size(videos.filter{it.downloaded}.sumOf{it.bytes})}",style=MaterialTheme.typography.titleMedium,modifier=Modifier.padding(vertical=12.dp)) }
        if(videos.isEmpty()) item { Text("Готовые видео с компьютера появятся здесь после первой синхронизации. Скачивание продолжается с места обрыва.",color=MaterialTheme.colorScheme.onSurfaceVariant) }
        items(videos,key={it.id}) { v->ListItem(headlineContent={Text(v.title,maxLines=2,overflow=TextOverflow.Ellipsis)},supportingContent={Text(if(v.downloaded)"${v.height}p · ${size(v.bytes)} · на телефоне" else if(v.error.isNotEmpty())v.error else if(v.watched)"Просмотрено · не загружается повторно" else "Ждёт места · ${size(v.bytes)}")},leadingContent={LocalImage(repo.thumb(v.id),Modifier.size(80.dp,45.dp).clip(RoundedCornerShape(6.dp)))},trailingContent={if(!v.downloaded&&(v.watched||v.error.isNotEmpty()))IconButton(onClick={repo.requestDownload(v.id);SyncWorker.now(repo.context)}){Icon(Icons.Outlined.Replay,"Скачать ещё раз")}else Icon(if(v.downloaded)Icons.Outlined.OfflinePin else Icons.Outlined.Schedule,null)},colors=ListItemDefaults.colors(containerColor=Color.Transparent),modifier=Modifier.clickable(enabled=v.downloaded){onPlay(v)}) }
    }
}

@Composable private fun SectionTitle(text:String){Text(text,style=MaterialTheme.typography.titleMedium,fontWeight=FontWeight.SemiBold,color=MaterialTheme.colorScheme.primary,modifier=Modifier.padding(top=28.dp,bottom=12.dp))}
@Composable private fun ToggleSetting(repo:Repository,key:String,title:String,subtitle:String,default:Boolean,after:()->Unit={}){var checked by remember{mutableStateOf(repo.prefs.getBoolean(key,default))};ListItem(headlineContent={Text(title)},supportingContent={Text(subtitle)},trailingContent={Switch(checked=checked,onCheckedChange={checked=it;repo.prefs.edit().putBoolean(key,it).apply();after()})},colors=ListItemDefaults.colors(containerColor=Color.Transparent))}

@Composable
private fun PairDialog(repo:Repository,incoming:Pair<String,String>?,onDismiss:()->Unit,onPaired:()->Unit) {
    var address by remember(incoming){mutableStateOf(incoming?.first?:repo.address)};var code by remember(incoming){mutableStateOf(incoming?.second?:"")}
    var busy by remember{mutableStateOf(false)};var error by remember{mutableStateOf("")};val scope=rememberCoroutineScope()
    var searching by remember { mutableStateOf(false) }
    var computers by remember { mutableStateOf<List<LocalComputer>>(emptyList()) }
    var searchMessage by remember { mutableStateOf("") }
    suspend fun discover() {
        searching=true; searchMessage="Ищем компьютер в домашней сети…"
        try {
            computers=repo.findComputers()
            if(computers.size==1 && address.isBlank()) address=computers.single().address
            searchMessage=when(computers.size){0->"ПК пока не найден. Проверь домашний Wi‑Fi или введи адрес с экрана ПК.";1->"Компьютер найден. Введи код с его экрана.";else->"Выбери свой компьютер:"}
        } catch(e:kotlinx.coroutines.CancellationException){throw e}
          catch(e:Exception){searchMessage=repo.connectionError(e)}
        finally {searching=false}
    }
    LaunchedEffect(Unit){if(address.isBlank())discover()}
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},icon={Icon(Icons.Outlined.Devices,null)},title={Text("Подключить компьютер")},text={Column(verticalArrangement=Arrangement.spacedBy(16.dp)){
        Text("Открой Запас на ПК → Телефон → Подключить. Оба устройства должны быть в одной домашней сети Wi‑Fi.")
        if(searching)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(searchMessage.isNotEmpty())Text(searchMessage,style=MaterialTheme.typography.bodySmall)
        computers.forEach { computer -> TextButton(enabled=!busy,onClick={address=computer.address}){Text(computer.address)} }
        OutlinedTextField(value=address,onValueChange={address=it},label={Text("Адрес компьютера")},placeholder={Text("Определяется автоматически")},singleLine=true,enabled=!busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Uri),modifier=Modifier.fillMaxWidth())
        TextButton(enabled=!busy&&!searching,onClick={scope.launch{discover()}}){Text("Найти компьютер")}
        OutlinedTextField(value=code,onValueChange={code=it.filter(Char::isDigit).take(6)},label={Text("Одноразовый код")},singleLine=true,enabled=!busy,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.fillMaxWidth())
        if(error.isNotEmpty())Text(error,color=MaterialTheme.colorScheme.error,style=MaterialTheme.typography.bodySmall)
        if(busy)LinearProgressIndicator(Modifier.fillMaxWidth())
    }},confirmButton={TextButton(enabled=!busy&&!searching&&code.length==6,onClick={busy=true;error="";scope.launch{try{repo.pair(address,code);onPaired()}catch(e:kotlinx.coroutines.CancellationException){throw e}catch(e:Exception){error=e.message?:"Не удалось подключиться. Проверь адрес и Wi‑Fi."}finally{busy=false}}}){Text("Подключить")}},dismissButton={TextButton(enabled=!busy,onClick=onDismiss){Text("Отмена")}})
}

@Composable
private fun MiniPlayer(v:Video,player:MediaController?,onOpen:()->Unit,onClose:()->Unit) {
    var playing by remember{mutableStateOf(player?.isPlaying==true)}
    DisposableEffect(player){val l=object:Player.Listener{override fun onIsPlayingChanged(isPlaying:Boolean){playing=isPlaying}};player?.addListener(l);onDispose{player?.removeListener(l)}}
    Surface(color=MaterialTheme.colorScheme.surfaceContainerHigh){Row(Modifier.fillMaxWidth().heightIn(min=64.dp).clickable(onClick=onOpen).padding(start=12.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(v.title,maxLines=1,overflow=TextOverflow.Ellipsis,fontWeight=FontWeight.Medium,style=MaterialTheme.typography.bodyMedium);Text(v.channel,style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)};IconButton(onClick={if(playing)player?.pause() else player?.play()}){Icon(if(playing)Icons.Outlined.Pause else Icons.Outlined.PlayArrow,if(playing)"Пауза" else "Продолжить")};IconButton(onClick=onClose){Icon(Icons.Outlined.Close,"Закрыть плеер")}}}
}
