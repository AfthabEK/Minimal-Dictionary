package io.github.afthabek.minimaldictionary

import android.net.Uri
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.afthabek.minimaldictionary.data.DictionaryDb
import io.github.afthabek.minimaldictionary.ui.AboutScreen
import io.github.afthabek.minimaldictionary.ui.DownloadScreen
import io.github.afthabek.minimaldictionary.ui.EntryScreen
import io.github.afthabek.minimaldictionary.ui.SearchScreen
import io.github.afthabek.minimaldictionary.ui.theme.MinimalDictionaryTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MinimalDictionaryTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    App()
                }
            }
        }
    }
}

@Composable
private fun App() {
    val context = LocalContext.current
    var ready by remember { mutableStateOf(DictionaryDb.isInstalled(context)) }

    val content: @Composable () -> Unit = {
        if (!ready) {
            DownloadScreen(onReady = { ready = true })
        } else {
            val nav = rememberNavController()
            NavHost(navController = nav, startDestination = "search") {
                composable("search") {
                    SearchScreen(
                        onOpenWord = { nav.navigate("entry/${Uri.encode(it)}") },
                        onAbout = { nav.navigate("about") },
                    )
                }
                composable("entry/{word}") { backStackEntry ->
                    val word = backStackEntry.arguments?.getString("word").orEmpty()
                    EntryScreen(
                        word = word,
                        onWordTap = { nav.navigate("entry/${Uri.encode(it)}") },
                        onBack = { nav.popBackStack() },
                    )
                }
                composable("about") {
                    AboutScreen(onBack = { nav.popBackStack() })
                }
            }
        }
    }

    androidx.compose.foundation.layout.Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        content()
    }
}
