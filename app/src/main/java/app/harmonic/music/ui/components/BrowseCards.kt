package app.harmonic.music.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Album
import androidx.compose.material.icons.filled.Person
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.harmonic.music.R
import app.harmonic.music.data.model.ArtistRef
import app.harmonic.music.data.model.MusicSource
import app.harmonic.music.data.remote.BrowseItem

/** A square (or round, for artists) card in an artist page carousel. */
@Composable
fun BrowseCard(item: BrowseItem, onClick: () -> Unit) {
    val isArtist = item is BrowseItem.Artist
    Column(
        Modifier
            .width(140.dp)
            .clickable(onClick = onClick)
            .padding(4.dp),
        horizontalAlignment = if (isArtist) Alignment.CenterHorizontally else Alignment.Start,
    ) {
        Artwork(
            item.thumbnailUrl,
            Modifier.size(132.dp).then(if (isArtist) Modifier.clip(CircleShape) else Modifier),
            corner = if (isArtist) 66.dp else 8.dp,
            placeholder = if (isArtist) Icons.Filled.Person else Icons.Filled.Album,
        )
        Spacer(Modifier.height(6.dp))
        Text(
            item.title,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = if (isArtist) TextAlign.Center else TextAlign.Start,
        )
        if (item.subtitle.isNotEmpty()) {
            Text(
                item.subtitle,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** An artist in search results. */
@Composable
fun ArtistRow(artist: ArtistRef, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Artwork(artist.thumbnailUrl, Modifier.size(56.dp), corner = 28.dp, placeholder = Icons.Filled.Person)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyLarge)
            Row(verticalAlignment = Alignment.CenterVertically) {
                SourceBadge(MusicSource.YOUTUBE)
                Text(
                    stringResource(R.string.artist),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}
