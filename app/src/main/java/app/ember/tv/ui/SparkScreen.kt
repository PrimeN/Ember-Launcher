package app.ember.tv.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import app.ember.tv.HomeViewModel
import app.ember.tv.isSaved
import app.ember.tv.data.Meta

/** #435 Spark in Discover: what it has learned, then its named rows of picks, each with a reason. */
@Composable
internal fun SparkScreen(vm: HomeViewModel, onOpen: (Meta) -> Unit) {
    val spark = vm.spark
    LazyColumn(Modifier.fillMaxSize().padding(horizontal = TvTokens.Space.Page.dp),
        contentPadding = PaddingValues(top = 8.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        item(key = "spark-head") {
            val learned = spark.summary
            Text(
                when {
                    !app.ember.tv.tracker.Tmdb.available -> ""
                    learned != null -> "Spark has noticed you like $learned" +
                        (if (spark.builtAt > 0) " · picked ${app.ember.tv.data.DisplayDates.dayTime(spark.builtAt)}" else "") +
                        (if (spark.loading) " · looking for more…" else "")
                    spark.loading -> "Spark is looking through what you've watched…"
                    else -> ""
                },
                color = Mute, fontSize = TvTokens.Type.Caption.sp, maxLines = 2,
            )
        }
        when {
            !app.ember.tv.tracker.Tmdb.available -> item(key = "spark-key") {
                RecoveryPanel("Spark needs a TMDB key", "Spark finds titles like the ones you watch through TMDB. Add a free TMDB key to turn it on.", "API keys") { vm.openSettingsPage("KEYS") }
            }
            spark.rows.isEmpty() && spark.loading -> item(key = "spark-loading") { LoadingCards(false) }
            spark.rows.isEmpty() && spark.error != null -> item(key = "spark-error") {
                RecoveryPanel("Spark couldn't make picks", spark.error.orEmpty(), "Try again") { spark.refresh() }
            }
            spark.rows.isEmpty() -> item(key = "spark-empty") {
                RecoveryPanel("Spark gets to know you as you watch",
                    "Play a few films or shows on this profile, or connect Trakt or another tracker, and Spark will pick titles for you here. It learns on this TV and keeps nothing anywhere else.")
            }
        }
        items(spark.rows, key = { it.id }) { row ->
            ModernSection(row.title, row.subtitle) {
                MediaRow(row.items, wide = false, isSaved = { vm.isSaved(it) }, onOpen = onOpen)
            }
        }
    }
}
