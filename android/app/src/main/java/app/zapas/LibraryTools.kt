package app.zapas

import java.util.Locale

object SmartSearch {
    fun normalize(text: String) = text.lowercase(Locale.ROOT).replace('ё','е').replace(Regex("[^\\p{L}\\p{N}]+"), " ").trim()
    private fun near(a: String, b: String): Boolean {
        if(a.length < 4 || kotlin.math.abs(a.length-b.length)>1) return false
        var previous=IntArray(b.length+1){it}
        a.forEachIndexed { i,c ->
            val next=IntArray(b.length+1);next[0]=i+1
            b.forEachIndexed { j,d -> next[j+1]=minOf(next[j]+1,previous[j+1]+1,previous[j]+if(c==d)0 else 1) }
            previous=next
        }
        return previous.last()<=1
    }
    fun find(videos: List<Video>, query: String): List<Video> {
        if(query.isBlank()) return videos
        val raw=normalize(query)
        val short=raw.contains("короткие");val long=raw.contains("длинные")
        val unwatched=raw.contains("непросмотренные");val resume=raw.contains("досмотреть")
        val under=Regex("до (\\d+) мин").find(raw)?.groupValues?.get(1)?.toIntOrNull()
        val words=raw.replace(Regex("до \\d+ мин(ут)?"),"").split(' ').filter{it.isNotBlank()&&it !in setOf("короткие","длинные","непросмотренные","досмотреть")}
        return videos.mapNotNull { v ->
            if(short&&v.duration>600||long&&v.duration<1200||unwatched&&v.watched||resume&&(v.position==0L||v.watched)||under!=null&&v.duration>under*60) return@mapNotNull null
            val title=normalize(v.title);val channel=normalize(v.channel);val tokens=(title+" "+channel).split(' ')
            var score=0
            for(word in words) {
                score+=when {title.contains(word)->10;channel.contains(word)->6;tokens.any{near(word,it)}->2;else->return@mapNotNull null}
            }
            v to score
        }.sortedByDescending{it.second}.map{it.first}
    }
}

data class SpaceUsage(val videos: Long, val partial: Long, val thumbnails: Long, val free: Long) {
    val used get()=videos+partial+thumbnails
}

data class DownloadLimits(val maxCount:Int,val budget:Long,val reserve:Long,val maxItem:Long,val maxDuration:Double) {
    fun reason(v:Video,count:Int,used:Long,free:Long,partial:Long):String? {
        val remaining=(v.bytes-partial).coerceAtLeast(0)+64*1024
        return when {
            count>=maxCount->"Достигнут лимит количества видео"
            maxItem>0&&v.bytes>maxItem->"Ролик больше лимита размера одного файла"
            maxDuration>0&&v.duration>maxDuration->"Ролик длиннее выбранного лимита"
            used+remaining>budget->"Не помещается в бюджет приложения"
            free-remaining<reserve->"Сохраняем свободное место для телефона"
            else->null
        }
    }
}
