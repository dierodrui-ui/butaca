package app.butaca.tv.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.butaca.tv.ButacaApp
import app.butaca.tv.LocalNav
import app.butaca.tv.data.ContentType
import app.butaca.tv.data.ItemEntity
import app.butaca.tv.data.PersonPage
import app.butaca.tv.data.personPage
import app.butaca.tv.data.toRef
import app.butaca.tv.ui.C
import app.butaca.tv.ui.components.EmptyState
import app.butaca.tv.ui.components.MenuRequest
import app.butaca.tv.ui.components.PivotScope
import app.butaca.tv.ui.components.PosterCard
import app.butaca.tv.ui.components.SectionTitle
import app.butaca.tv.ui.components.T
import app.butaca.tv.ui.components.itemMeta
import coil.compose.AsyncImage

/** Página de un actor, actriz o director: biografía y todo lo suyo que tienes en tu servidor. */
@Composable
fun PersonScreen(id: Int, onBack: () -> Unit) {
    val repo = ButacaApp.repo
    val nav = LocalNav.current
    BackHandler { onBack() }
    val page by produceState<PersonPage?>(null, id) { value = repo.personPage(id) }
    val p = page

    if (p == null) {
        Box(Modifier.fillMaxSize().background(C.Bg)) { T("Cargando…", color = C.Muted, modifier = Modifier.padding(48.dp)) }
        return
    }

    PivotScope(pivot = 60.dp) {
        LazyVerticalGrid(
            columns = GridCells.Adaptive(108.dp),
            modifier = Modifier.fillMaxSize().background(C.Bg),
            contentPadding = PaddingValues(start = 48.dp, end = 48.dp, top = 30.dp, bottom = 80.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item(key = "header", span = { GridItemSpan(maxLineSpan) }) {
                Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
                    Box(Modifier.width(170.dp).aspectRatio(2f / 3f).clip(RoundedCornerShape(12.dp)).background(C.Raised)) {
                        if (p.info.photo != null) AsyncImage(p.info.photo, p.info.name, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        T(p.info.name, size = 32, weight = FontWeight.Bold)
                        val dept = when (p.info.department) {
                            "Acting" -> "Interpretación"
                            "Directing" -> "Dirección"
                            "Writing" -> "Guion"
                            "Production" -> "Producción"
                            else -> p.info.department
                        }
                        val meta = listOfNotNull(
                            dept,
                            p.info.birthday?.let { "Nacimiento: ${formatDate(it)}" },
                            p.info.place,
                        ).joinToString(" · ")
                        if (meta.isNotBlank()) T(meta, size = 14, color = C.Muted)
                        p.info.bio?.let { T(it, size = 14, color = C.TextSoft, maxLines = 7, lineHeight = 20, modifier = Modifier.fillMaxWidth(0.95f)) }
                        T("${p.directed.size + p.acted.size} títulos suyos en tu servidor", size = 13, weight = FontWeight.SemiBold, color = C.Accent)
                    }
                }
            }
            if (p.directed.isEmpty() && p.acted.isEmpty()) {
                item(key = "none", span = { GridItemSpan(maxLineSpan) }) {
                    EmptyState("No hay nada suyo en tu servidor", "Prueba con otra persona del reparto.")
                }
            }
            section(this, "Como director/a", p.directed, nav, repo)
            section(this, "Como actor o actriz", p.acted, nav, repo)
        }
    }
}

private fun section(
    scope: androidx.compose.foundation.lazy.grid.LazyGridScope,
    title: String,
    list: List<ItemEntity>,
    nav: app.butaca.tv.AppNav,
    repo: app.butaca.tv.data.Repo,
) {
    if (list.isEmpty()) return
    scope.item(key = "h_$title", span = { GridItemSpan(maxLineSpan) }) { SectionTitle(title, Modifier.padding(top = 10.dp)) }
    scope.items(list, key = { "${title}_${it.key}" }) { item ->
        LaunchedEffect(item.key) { repo.requestGenre(item) }
        PosterCard(
            title = item.name, icon = item.icon, keySeed = item.stableKey, width = null,
            meta = itemMeta(item), isSeries = item.type == ContentType.SERIES,
            onClick = { nav.openItem(item) },
            onLongClick = { nav.showMenu(MenuRequest(item.toRef())) },
        )
    }
}
