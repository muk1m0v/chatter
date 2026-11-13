package local.codex.composecheck

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    var count by remember { mutableStateOf(0) }
                    Column(modifier = Modifier.padding(28.dp).statusBarsPadding()) {
                        Text("Проверка Kotlin + Compose", style = MaterialTheme.typography.headlineSmall)
                        Spacer(Modifier.height(20.dp))
                        Text("Нативное Android-приложение. Собрано без Android Studio.")
                        Spacer(Modifier.height(20.dp))
                        Button(onClick = { count++ }) { Text("Проверить кнопку") }
                        Text("Нажатий: $count")
                    }
                }
            }
        }
    }
}
