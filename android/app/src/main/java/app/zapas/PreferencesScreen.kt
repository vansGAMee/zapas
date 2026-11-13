package app.zapas

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.launch

@Composable
internal fun SettingsScreen(repo:Repository,dark:Boolean,onDark:(Boolean)->Unit,onPair:()->Unit,onSync:()->Unit,onDisconnect:()->Unit,onClear:()->Unit) {
    var section by rememberSaveable{mutableStateOf("Память")}
    var revision by remember{mutableIntStateOf(0)}
    val videos by repo.videos.collectAsStateWithLifecycle()
    val syncing by repo.syncing.collectAsStateWithLifecycle()
    val scope=rememberCoroutineScope()
    var cleanup by remember{mutableStateOf(false)}
    val usage=remember(videos,revision,syncing){repo.space()}
    val limits=remember(revision){repo.limits()}
    fun changed(){revision++}
    Box(Modifier.fillMaxSize(),contentAlignment=Alignment.TopCenter){
    LazyColumn(Modifier.widthIn(max=840.dp).fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        item { Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)){Icon(Icons.Rounded.Settings,null,Modifier.size(28.dp));Text("Настройки",style=MaterialTheme.typography.headlineMedium,fontWeight=FontWeight.SemiBold)} }
        item { Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf("Память","Скачивание","Видео","Вид").forEach{tab->FilterChip(selected=section==tab,onClick={section=tab},label={Text(tab)})}} }
        if(section=="Память") {
            item { Surface(color=MaterialTheme.colorScheme.surfaceContainer,shape=MaterialTheme.shapes.large){Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
                Text("${size(usage.used)} из ${size(limits.budget)}",style=MaterialTheme.typography.headlineSmall,fontWeight=FontWeight.SemiBold)
                LinearProgressIndicator(progress={(usage.used.toDouble()/limits.budget.coerceAtLeast(1)).toFloat().coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth())
                Text("${videos.count{it.downloaded}} видео · ${size(usage.partial)} недокачано · ${size(usage.thumbnails)} обложки",style=MaterialTheme.typography.bodySmall)
                Text("На телефоне свободно ${size(usage.free)}. Резерв: ${size(limits.reserve)}.",color=MaterialTheme.colorScheme.onSurfaceVariant)
                if(usage.used>limits.budget)Text("Лимит уменьшен. Новые видео ждут места; существующие не удаляются без твоего выбора.",color=MaterialTheme.colorScheme.error)
            }} }
            item { PreferenceToggle(repo,"downloads_paused","Пауза всех загрузок","Скачанные видео продолжают работать",false,onChange={repo.pauseDownloads(it);changed()}) }
            item { PreferenceToggle(repo,"auto_budget","Автоматический бюджет","Доля свободного места с учётом резерва",false,onChange={changed()}) }
            if(repo.prefs.getBoolean("auto_budget",false)) item { IntegerPreference(repo,"budget_percent","Доля доступного места","Проценты, от 1 до 80",10,1..80,"%",::changed) }
            else item { IntegerPreference(repo,"max_mb","Бюджет приложения","Учитываются видео, обложки и недокачанные файлы",8192,256..262144,"МБ",::changed) }
            item { Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){listOf(512,1024,2048,4096,8192,16384).forEach{mb->SuggestionChip(onClick={repo.prefs.edit().putInt("max_mb",mb).putBoolean("auto_budget",false).apply();changed()},label={Text(size(mb.toLong()*1024*1024))})}} }
            item { IntegerPreference(repo,"max_videos","Количество видео","Ограничено также бюджетом памяти",50,1..1000,"видео",::changed) }
            item { IntegerPreference(repo,"reserve_mb","Оставлять телефону свободно","Загрузка остановится до заполнения этого резерва",1024,256..32768,"МБ",::changed) }
            item { IntegerPreference(repo,"max_item_mb","Размер одного ролика","0 — без отдельного ограничения",0,0..32768,"МБ",::changed) }
            item { IntegerPreference(repo,"max_duration_min","Длительность одного ролика","0 — любая длительность",0,0..360,"мин",::changed) }
            item { PreferenceToggle(repo,"delete_watched","Очищать просмотренное","Только при синхронизации. Закреплённое и текущий ролик защищены",true) }
            item { IntegerPreference(repo,"keep_watched_days","Хранить после просмотра","0 — удалять при следующем пополнении",0,0..90,"дней",::changed) }
            item { PreferenceToggle(repo,"protect_saved","Беречь «Смотреть позже» и плейлисты","Не удалять их при автоматической очистке",true) }
            item { OutlinedButton(onClick=onClear,modifier=Modifier.fillMaxWidth()){Icon(Icons.Outlined.DeleteSweep,null,Modifier.size(20.dp));Spacer(Modifier.width(8.dp));Text("Очистить просмотренное")} }
            item { OutlinedButton(onClick={cleanup=true},modifier=Modifier.fillMaxWidth()){Text("Освободить место вручную")} }
            item { TextButton(enabled=!syncing&&usage.partial>0,onClick={scope.launch{repo.clearPartial();changed()}}){Text("Убрать недокачанное · ${size(usage.partial)}")} }
        }
        if(section=="Скачивание") {
            item { ListItem(headlineContent={Text(if(repo.paired)"Домашний ПК подключён" else "Подключить домашний ПК")},supportingContent={Text(repo.address.ifEmpty{"Поиск в домашнем Wi‑Fi и одноразовый код"})},leadingContent={Icon(Icons.Outlined.Computer,null,Modifier.size(24.dp))},modifier=Modifier.clickable(onClick=onPair)) }
            item { Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){FilledTonalButton(onClick=onSync,enabled=!repo.phonePaused){Text("Проверить сейчас")};if(repo.paired)TextButton(onClick=onDisconnect){Text("Отключить")}} }
            item { PreferenceToggle(repo,"auto_sync","Пополнять автоматически","Пока приложение открыто — каждые 30 секунд. В фоне — по расписанию Android",true,onChange={SyncWorker.schedule(repo.context)}) }
            item { Choices(repo,"sync_minutes","Проверять в фоне",listOf("15" to "15 мин","30" to "30 мин","60" to "1 час","180" to "3 часа"),"15",integer=true,after={SyncWorker.schedule(repo.context)}) }
            item { PreferenceToggle(repo,"charging_only","В фоне только на зарядке","Ручная проверка доступна в любое время",false,onChange={SyncWorker.schedule(repo.context)}) }
            item { Choices(repo,"download_order","Что скачать первым",listOf("small" to "Меньше размер","short" to "Короче","new" to "Новое","later" to "Смотреть позже"),"small") }
            item { IntegerPreference(repo,"session_mb","За один сеанс передачи","0 — до общего лимита. Приоритет отдается файлам, которые помещаются",0,0..65536,"МБ",::changed) }
            item { Text("Передача идёт по домашней сети, включая Wi‑Fi без доступа к интернету. VPN не подменяет адрес ПК. Если ПК готовит видео или поставлен на паузу, это видно в «Загрузках».",color=MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        if(section=="Видео") {
            item { Text("Новые ролики готовит ПК. Настройки передадутся при следующей синхронизации. Готовые файлы сохраняют своё качество.",color=MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Choices(repo,"profile","Разрешение",listOf("compact" to "480p · экономно","balanced" to "720p · баланс","quality" to "1080p"),"balanced",quality=true) }
            item { Choices(repo,"codec","Кодек",listOf("h264" to "H.264","hevc" to "HEVC","av1" to "AV1"),"h264",quality=true) }
            item { Text("H.264 совместим почти со всеми телефонами. HEVC и AV1 экономят память на устройствах с поддержкой этих кодеков.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Choices(repo,"encoding_mode","Как готовить",listOf("smart" to "Умно","fast" to "Быстрее","small" to "Компактнее"),"smart",quality=true) }
            item { Text("Умный режим сохраняет уже компактную совместимую видеодорожку без повторного сжатия. «Компактнее» тратит больше времени на кодирование. Размер зависит от самого видео.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant) }
            item { PreferenceToggle(repo,"autoplay","Следующий автоматически","Продолжать непросмотренными видео из запаса",true) }
            item { Choices(repo,"seek_seconds","Шаг перемотки",listOf("5" to "5 с","10" to "10 с","15" to "15 с","30" to "30 с"),"10",integer=true) }
            item { PreferenceToggle(repo,"pip","Картинка в картинке","Продолжать видео поверх других приложений",true) }
        }
        if(section=="Вид") {
            item { ListItem(headlineContent={Text("Тёмная тема")},trailingContent={Switch(checked=dark,onCheckedChange=onDark)}) }
            item { Choices(repo,"screen_mode","Большой экран",listOf("auto" to "Автоматически","full" to "Только видео","details" to "Видео и лента"),"auto") }
            item { Choices(repo,"video_fit","Масштаб видео",listOf("fit" to "Целиком","zoom" to "Заполнить"),"fit") }
            item { Text("На планшете навигация переезжает в боковую панель. На широком экране плеер и следующее видео стоят рядом. На телефоне при повороте включается полноэкранный просмотр.",color=MaterialTheme.colorScheme.onSurfaceVariant) }
            item { Text("Умный поиск понимает название, канал, небольшие опечатки и запросы «короткие», «непросмотренные», «досмотреть», «до 10 мин». Поиск, история, понравившиеся и плейлисты работают офлайн.",color=MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        item { Text("Запас 0.2.0 · личная офлайн-видеотека",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,modifier=Modifier.padding(vertical=20.dp)) }
    }}
    if(cleanup) CleanupDialog(repo,onDismiss={cleanup=false;changed()})
}

@Composable private fun PreferenceToggle(repo:Repository,key:String,title:String,description:String,default:Boolean,onChange:(Boolean)->Unit={}) {
    var value by remember{mutableStateOf(repo.prefs.getBoolean(key,default))}
    ListItem(headlineContent={Text(title)},supportingContent={Text(description)},trailingContent={Switch(checked=value,onCheckedChange={value=it;repo.prefs.edit().putBoolean(key,it).apply();onChange(it)})},colors=ListItemDefaults.colors(containerColor=MaterialTheme.colorScheme.surface))
}
@Composable private fun IntegerPreference(repo:Repository,key:String,title:String,description:String,default:Int,range:IntRange,unit:String,after:()->Unit) {
    var editing by remember{mutableStateOf(false)};var text by remember{mutableStateOf("")};val value=repo.prefs.getInt(key,default)
    ListItem(headlineContent={Text(title)},supportingContent={Text(description)},trailingContent={Text("$value $unit",color=MaterialTheme.colorScheme.primary)},modifier=Modifier.clickable{editing=true;text=value.toString()},colors=ListItemDefaults.colors(containerColor=MaterialTheme.colorScheme.surface))
    if(editing) { val parsed=text.toIntOrNull();val valid=parsed!=null&&parsed in range
        AlertDialog(onDismissRequest={editing=false},title={Text(title)},text={OutlinedTextField(value=text,onValueChange={text=it.filter(Char::isDigit).take(7)},label={Text(unit)},keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),supportingText={Text("От ${range.first} до ${range.last}. $description")},isError=!valid,singleLine=true)},confirmButton={TextButton(enabled=valid,onClick={repo.prefs.edit().putInt(key,parsed!!).apply();editing=false;after()}){Text("Применить")}},dismissButton={TextButton(onClick={editing=false}){Text("Отмена")}})
    }
}
@Composable private fun Choices(repo:Repository,key:String,title:String,options:List<Pair<String,String>>,default:String,quality:Boolean=false,integer:Boolean=false,after:()->Unit={}) {
    var value by remember{mutableStateOf(if(integer)repo.prefs.getInt(key,default.toInt()).toString() else repo.prefs.getString(key,default).orEmpty())}
    Column(Modifier.padding(vertical=12.dp)){Text(title,style=MaterialTheme.typography.titleMedium);Row(Modifier.horizontalScroll(rememberScrollState()),horizontalArrangement=Arrangement.spacedBy(8.dp)){options.forEach{(id,label)->FilterChip(selected=value==id,onClick={value=id;repo.prefs.edit().apply{if(integer)putInt(key,id.toInt()) else putString(key,id);if(quality)putBoolean("pending_quality",true)}.apply();after()},label={Text(label)})}}}
}
@Composable private fun CleanupDialog(repo:Repository,onDismiss:()->Unit) {
    val videos by repo.videos.collectAsStateWithLifecycle();var selected by remember{mutableStateOf(setOf<String>())};var busy by remember{mutableStateOf(false)};val scope=rememberCoroutineScope()
    val eligible=videos.filter{it.downloaded&&!it.pinned&&it.id!=repo.playingId}.sortedByDescending{it.bytes}
    AlertDialog(onDismissRequest={if(!busy)onDismiss()},title={Text("Освободить место")},text={Column{Text("Сначала самые большие. Закреплённое и текущий ролик не затрагиваются.");LazyColumn(Modifier.heightIn(max=360.dp)){items(eligible.size){i->val v=eligible[i];ListItem(headlineContent={Text(v.title,maxLines=2)},supportingContent={Text(size(v.bytes))},leadingContent={Checkbox(checked=v.id in selected,onCheckedChange={selected=if(it)selected+v.id else selected-v.id})})}}}},confirmButton={TextButton(enabled=selected.isNotEmpty()&&!busy,onClick={busy=true;scope.launch{repo.mutex.lock();try{selected.forEach{repo.remove(it)}}finally{repo.mutex.unlock()};onDismiss()}}){Text("Удалить · ${size(eligible.filter{it.id in selected}.sumOf{it.bytes})}")}},dismissButton={TextButton(enabled=!busy,onClick=onDismiss){Text("Отмена")}})
}
