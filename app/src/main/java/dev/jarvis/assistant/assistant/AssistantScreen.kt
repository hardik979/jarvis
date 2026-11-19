package dev.jarvis.assistant.assistant

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import dev.jarvis.assistant.ui.Orb

@Composable
fun AssistantScreen(state: AssistantUiState, onOrbTap: () -> Unit, onDismiss: () -> Unit) {
    val entered = remember { MutableTransitionState(false).apply { targetState = true } }

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black.copy(alpha = 0.3f))
            .clickable(interactionSource = null, indication = null, onClick = onDismiss)
    ) {
        AnimatedVisibility(
            visibleState = entered,
            modifier = Modifier.align(Alignment.BottomCenter),
            enter = slideInVertically { it } + fadeIn(),
        ) {
            Surface(
                // Taps on the card itself shouldn't dismiss the overlay.
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(interactionSource = null, indication = null) {},
                shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp),
                tonalElevation = 6.dp,
            ) {
                Column(
                    Modifier
                        .navigationBarsPadding()
                        .padding(horizontal = 24.dp, vertical = 20.dp)
                        .animateContentSize(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        state.statusLabel(),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (state.heard.isNotEmpty()) {
                        Text(state.heard, style = MaterialTheme.typography.headlineSmall, textAlign = TextAlign.Center)
                    }
                    if (state.reply.isNotEmpty()) {
                        Text(
                            state.reply,
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.primary,
                            textAlign = TextAlign.Center,
                        )
                    }
                    state.error?.let {
                        Text(it, color = MaterialTheme.colorScheme.error, textAlign = TextAlign.Center)
                    }
                    state.debug?.takeIf { it.isNotEmpty() }?.let {
                        Text(
                            it,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center,
                        )
                    }
                    Orb(
                        phase = state.phase,
                        level = state.level,
                        modifier = Modifier
                            .size(88.dp)
                            .clip(CircleShape)
                            .clickable(onClick = onOrbTap),
                    )
                }
            }
        }
    }
}

private fun AssistantUiState.statusLabel() = when (phase) {
    Phase.Listening -> if (waitingForMore) "Go on, I'm listening… (tap when done)" else "Listening… (tap when done)"
    Phase.Thinking -> "Thinking…"
    Phase.Speaking -> "Jarvis"
    Phase.Idle -> "Tap the orb to talk"
}
