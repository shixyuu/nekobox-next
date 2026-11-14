package io.nekobox.next.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ContainedLoadingIndicator
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialShapes
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.toShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Matrix
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.foundation.Canvas
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.toPath
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.graphics.shapes.Morph
import io.nekobox.next.R
import io.nekobox.next.session.Phase

enum class TopDest(val label: Int) { Home(R.string.nav_home), Nodes(R.string.nav_nodes), Route(R.string.nav_route), More(R.string.nav_more) }

@Composable
fun FloatingNav(current: TopDest, onSelect: (TopDest) -> Unit, modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.navigationBarsPadding().padding(horizontal = 16.dp, vertical = 10.dp),
        shape = RoundedCornerShape(28.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        shadowElevation = 8.dp,
        tonalElevation = 3.dp,
    ) {
        Row(Modifier.padding(6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            TopDest.entries.forEach { dest ->
                val selected = dest == current
                val weight by animateFloatAsState(if (selected) 1.35f else 1f, MaterialTheme.motionScheme.fastSpatialSpec())
                Box(
                    Modifier
                        .weight(weight)
                        .clip(RoundedCornerShape(22.dp))
                        .background(if (selected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer)
                        .clickable { onSelect(dest) }
                        .padding(vertical = 12.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        stringResource(dest.label),
                        style = if (selected) MaterialTheme.typography.labelLargeEmphasized else MaterialTheme.typography.labelLarge,
                        color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ConnectButton(phase: Phase, onClick: () -> Unit, size: Dp) {
    val morph = remember { Morph(MaterialShapes.Sunny, MaterialShapes.Flower) }
    val progress by animateFloatAsState(
        targetValue = if (phase == Phase.Connected) 1f else 0f,
        animationSpec = MaterialTheme.motionScheme.defaultSpatialSpec(),
    )
    val color = if (phase == Phase.Connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.primaryContainer
    Box(Modifier.size(size).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        if (phase == Phase.Connecting) {
            ContainedLoadingIndicator(Modifier.fillMaxSize())
        } else {
            Canvas(Modifier.fillMaxSize()) {
                val path = morph.toPath(progress, Path())
                val bounds = path.getBounds()
                if (bounds.width > 0f && bounds.height > 0f) {
                    path.transform(Matrix().apply {
                        translate(x = -bounds.left, y = -bounds.top)
                        scale(x = this@Canvas.size.width / bounds.width, y = this@Canvas.size.height / bounds.height)
                    })
                    drawPath(path, color)
                }
            }
            if (phase == Phase.Connected) {
                Icon(Icons.Filled.Check, contentDescription = stringResource(R.string.status_connected), modifier = Modifier.size(72.dp), tint = MaterialTheme.colorScheme.onPrimary)
            } else {
                Text(stringResource(R.string.action_connect), style = MaterialTheme.typography.headlineMediumEmphasized, color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ProtocolAvatar(protocol: String, modifier: Modifier = Modifier) {
    val polygon = when (protocol) {
        "vless" -> MaterialShapes.Gem
        "vmess" -> MaterialShapes.Pentagon
        "trojan" -> MaterialShapes.Cookie6Sided
        "shadowsocks" -> MaterialShapes.Clover4Leaf
        "hysteria2", "hysteria" -> MaterialShapes.Burst
        "tuic" -> MaterialShapes.Sunny
        "wireguard" -> MaterialShapes.Diamond
        "byedpi" -> MaterialShapes.Flower
        "http", "socks" -> MaterialShapes.Pill
        else -> MaterialShapes.Oval
    }
    Box(
        modifier.size(44.dp).clip(polygon.toShape()).background(MaterialTheme.colorScheme.secondaryContainer),
        contentAlignment = Alignment.Center,
    ) {
        Text(protocol.take(2).uppercase(), style = MaterialTheme.typography.labelSmall, textAlign = TextAlign.Center)
    }
}

@Composable
fun MoreTile(title: String, icon: ImageVector, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Surface(
        onClick = onClick,
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
            Text(title, style = MaterialTheme.typography.titleMediumEmphasized)
        }
    }
}
