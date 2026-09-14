package applock.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Fingerprint
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Security
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

@Composable
fun OnboardingScreen(
    onContinue: () -> Unit
) {

    var page by remember {
        mutableIntStateOf(0)
    }

    val slides = listOf(
        Triple(
            Icons.Default.Lock,
            "Private by design",
            "Protect the apps that matter without sending passwords or screen content to a server."
        ),
        Triple(
            Icons.Default.Security,
            "Lock what matters",
            "Choose apps, configure your session rule, and see truthful protection health at a glance."
        ),
        Triple(
            Icons.Default.Fingerprint,
            "Fast authentication",
            "Use PIN, pattern or Android biometric authentication. Security never waits for ads or network work."
        )
    )

    val item = slides[page]

    Box(
        modifier = Modifier
            .background(
                Brush.verticalGradient(
                    listOf(
                        MaterialTheme.colorScheme.background,
                        MaterialTheme.colorScheme.surfaceVariant
                    )
                )
            )
            .safeDrawingPadding()
            .navigationBarsPadding()
    ) {

        LazyColumn(
            modifier = Modifier.fillMaxWidth(),

            horizontalAlignment =
                Alignment.CenterHorizontally,

            verticalArrangement =
                Arrangement.Center,

            contentPadding =
                androidx.compose.foundation.layout.PaddingValues(
                    horizontal = 28.dp,
                    vertical = 28.dp
                )
        ) {

            item {

                Column(
                    modifier =
                        Modifier.fillMaxWidth(),

                    horizontalAlignment =
                        Alignment.CenterHorizontally
                ) {

                    /*
                     * Keep the visual identity strong, but avoid
                     * excessive fixed vertical spacing.
                     */
                    Box(
                        modifier =
                            Modifier.size(112.dp)
                                .background(
                                    MaterialTheme
                                        .colorScheme
                                        .primary
                                        .copy(alpha = .12f),
                                    CircleShape
                                ),

                        contentAlignment =
                            Alignment.Center
                    ) {

                        Icon(
                            imageVector = item.first,
                            contentDescription = null,
                            tint =
                                MaterialTheme
                                    .colorScheme
                                    .primary,
                            modifier =
                                Modifier.size(56.dp)
                        )
                    }

                    Spacer(
                        Modifier.height(24.dp)
                    )

                    Text(
                        text = "AppLock",
                        style =
                            MaterialTheme
                                .typography
                                .displaySmall,
                        fontWeight =
                            FontWeight.Bold,
                        textAlign =
                            TextAlign.Center
                    )

                    Spacer(
                        Modifier.height(8.dp)
                    )

                    Text(
                        text = item.second,
                        style =
                            MaterialTheme
                                .typography
                                .headlineSmall,
                        fontWeight =
                            FontWeight.SemiBold,
                        textAlign =
                            TextAlign.Center
                    )

                    Spacer(
                        Modifier.height(12.dp)
                    )

                    Text(
                        text = item.third,
                        style =
                            MaterialTheme
                                .typography
                                .bodyLarge,
                        color =
                            MaterialTheme
                                .colorScheme
                                .onSurfaceVariant,
                        textAlign =
                            TextAlign.Center,
                        modifier =
                            Modifier.fillMaxWidth()
                    )

                    Spacer(
                        Modifier.height(24.dp)
                    )

                    Row(
                        horizontalArrangement =
                            Arrangement.spacedBy(8.dp),

                        verticalAlignment =
                            Alignment.CenterVertically
                    ) {

                        repeat(slides.size) { index ->

                            Box(
                                modifier =
                                    Modifier
                                        .size(
                                            width =
                                                if (index == page) {
                                                    28.dp
                                                } else {
                                                    8.dp
                                                },
                                            height = 8.dp
                                        )
                                        .background(
                                            color =
                                                if (index == page) {
                                                    MaterialTheme
                                                        .colorScheme
                                                        .primary
                                                } else {
                                                    MaterialTheme
                                                        .colorScheme
                                                        .outlineVariant
                                                },
                                            shape =
                                                RoundedCornerShape(
                                                    50
                                                )
                                        )
                            )
                        }
                    }

                    Spacer(
                        Modifier.height(24.dp)
                    )

                    Button(
                        onClick = {

                            if (page < slides.lastIndex) {
                                page++
                            } else {
                                onContinue()
                            }
                        },

                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .height(54.dp),

                        shape =
                            RoundedCornerShape(18.dp)
                    ) {

                        Text(
                            if (page < slides.lastIndex) {
                                "Continue"
                            } else {
                                "Set up protection"
                            }
                        )
                    }

                    if (page < slides.lastIndex) {

                        Spacer(
                            Modifier.height(8.dp)
                        )

                        TextButton(
                            onClick = onContinue
                        ) {

                            Text(
                                "Skip"
                            )
                        }
                    }
                }
            }
        }
    }
}