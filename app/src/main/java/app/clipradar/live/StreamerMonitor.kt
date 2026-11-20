package app.clipradar.live

import android.content.Context
import app.clipradar.data.AppDatabase

/** Стример, который только что вышел в эфир (или эфир идёт, а мы за ним не следим). */
data class LiveCandidate(val info: LiveInfo, val isNewStream: Boolean)

/** Проверка списка «Мои стримеры»: кто сейчас в эфире. */
object StreamerMonitor {
    suspend fun check(context: Context): List<LiveCandidate> {
        val dao = AppDatabase.get(context).streamers()
        val watched = dao.watched()
        if (watched.isEmpty()) return emptyList()
        val infos = TwitchApi.check(context, watched.map { it.login })
        val now = System.currentTimeMillis()
        val result = ArrayList<LiveCandidate>()
        for (s in watched) {
            val info = infos[s.login] ?: continue // не удалось проверить — попробуем позже
            if (info.live) {
                val isNew = !s.isLive || (info.streamId != null && info.streamId != s.currentStreamId)
                dao.update(
                    s.copy(
                        isLive = true,
                        currentStreamId = info.streamId ?: s.currentStreamId,
                        displayName = info.displayName.ifBlank { s.displayName },
                        lastCheckedAt = now,
                    ),
                )
                val ignored = info.streamId != null && info.streamId == s.ignoredStreamId
                if (!ignored && (isNew || !WatchService.isWatching(s.login))) result += LiveCandidate(info, isNew)
            } else {
                dao.update(s.copy(isLive = false, lastCheckedAt = now))
            }
        }
        return result
    }

    /** Пользователь нажал «Стоп» — не запускать наблюдение снова для этой же трансляции. */
    suspend fun ignoreCurrentStream(context: Context, login: String) {
        val dao = AppDatabase.get(context).streamers()
        val s = dao.get(login) ?: return
        if (s.currentStreamId != null) dao.update(s.copy(ignoredStreamId = s.currentStreamId))
    }
}
