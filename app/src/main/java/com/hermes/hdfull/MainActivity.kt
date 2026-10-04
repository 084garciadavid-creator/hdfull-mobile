package com.hermes.hdfull

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import com.hermes.hdfull.data.HdfullClient
import com.hermes.hdfull.data.SessionManager
import com.hermes.hdfull.ui.DetailScreen
import com.hermes.hdfull.ui.HDFullTheme
import com.hermes.hdfull.ui.HomeScreen
import com.hermes.hdfull.ui.LinksScreen
import com.hermes.hdfull.ui.LoginScreen
import com.hermes.hdfull.ui.MovieDetailScreen
import com.hermes.hdfull.ui.PlayerScreen

sealed interface Route {
    data object Login : Route
    data object Home : Route
    data class Detail(val url: String) : Route
    data class MovieDetail(val url: String) : Route
    data class Links(val url: String, val title: String) : Route
    data class Player(val url: String, val title: String) : Route
}

class Navigator(start: Route) {
    val stack = mutableStateListOf(start)
    val current: Route get() = stack.last()
    fun push(r: Route) { stack.add(r) }
    fun pop(): Boolean {
        if (stack.size > 1) { stack.removeAt(stack.lastIndex); return true }
        return false
    }
    fun replaceAll(r: Route) { stack.clear(); stack.add(r) }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val session = SessionManager(this)
        setContent {
            HDFullTheme {
                AppNav(session)
            }
        }
    }
}

@Composable
fun AppNav(session: SessionManager) {
    val nav = remember {
        Navigator(if (session.loggedIn && session.host.isNotBlank()) Route.Home else Route.Login)
    }
    BackHandler(enabled = nav.stack.size > 1) { nav.pop() }

    when (val r = nav.current) {
        is Route.Login -> LoginScreen(session = session, onLoggedIn = {
            nav.replaceAll(Route.Home)
        })
        is Route.Home -> HomeScreen(
            session = session,
            onOpenMovie = { url, _ -> nav.push(Route.MovieDetail(url)) },
            onOpenSeries = { url -> nav.push(Route.Detail(url)) },
            onLogout = {
                session.logout()
                HdfullClient.clearCookies()
                nav.replaceAll(Route.Login)
            }
        )
        is Route.Detail -> DetailScreen(
            session = session,
            url = r.url,
            onOpenEpisode = { epUrl, title -> nav.push(Route.Links(epUrl, title)) },
            onBack = { nav.pop() }
        )
        is Route.MovieDetail -> MovieDetailScreen(
            host = session.host,
            url = r.url,
            onOpenLinks = { movieUrl, title -> nav.push(Route.Links(movieUrl, title)) },
            onBack = { nav.pop() }
        )
        is Route.Links -> LinksScreen(
            url = r.url,
            title = r.title,
            onPlay = { embedUrl -> nav.push(Route.Player(embedUrl, r.title)) },
            onBack = { nav.pop() }
        )
        is Route.Player -> PlayerScreen(
            embedUrl = r.url,
            title = r.title,
            onBack = { nav.pop() }
        )
    }
}
