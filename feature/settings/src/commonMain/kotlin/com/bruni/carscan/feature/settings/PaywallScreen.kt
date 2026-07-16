package com.bruni.carscan.feature.settings

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.EventRepeat
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bruni.carscan.core.designsystem.generated.resources.Res
import com.bruni.carscan.core.designsystem.generated.resources.paywall_already_premium
import com.bruni.carscan.core.designsystem.generated.resources.paywall_cta_buy
import com.bruni.carscan.core.designsystem.generated.resources.paywall_cta_subscribe
import com.bruni.carscan.core.designsystem.generated.resources.paywall_lifetime_badge
import com.bruni.carscan.core.designsystem.generated.resources.paywall_lifetime_price
import com.bruni.carscan.core.designsystem.generated.resources.paywall_lifetime_title
import com.bruni.carscan.core.designsystem.generated.resources.paywall_monthly_price
import com.bruni.carscan.core.designsystem.generated.resources.paywall_monthly_title
import com.bruni.carscan.core.designsystem.generated.resources.paywall_plans_header
import com.bruni.carscan.core.designsystem.generated.resources.paywall_restore
import com.bruni.carscan.core.designsystem.generated.resources.paywall_subtitle
import com.bruni.carscan.core.designsystem.generated.resources.paywall_title
import com.bruni.carscan.core.designsystem.generated.resources.paywall_yearly_price
import com.bruni.carscan.core.designsystem.generated.resources.paywall_yearly_title
import com.bruni.carscan.core.monetization.PurchaseKind
import org.jetbrains.compose.resources.stringResource

/**
 * The paywall: Monthly and Yearly are plain cards, Lifetime is the hero — a gradient card, badged
 * as the recommended tier, matching the plan's positioning (yearly the cheap entry point,
 * lifetime the one-time upsell). Prices are DISPLAY placeholders ($1.99/$5.99/$9.99) until
 * [PurchaseKind]'s real `ProductDetails` price strings are threaded through from
 * `PlayBillingPort.queryProductDetails` — see this module's build notes.
 */
@Composable
fun PaywallScreen(
    state: PaywallState,
    onIntent: (PaywallIntent) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxWidth().padding(horizontal = 20.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                IconButton(onClick = { onIntent(PaywallIntent.Close) }) {
                    Icon(Icons.Rounded.Close, contentDescription = null)
                }
            }
        }

        item {
            Text(
                stringResource(Res.string.paywall_title),
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
            )
        }

        item {
            Text(
                stringResource(Res.string.paywall_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        if (state.isPremium) {
            item { AlreadyPremiumCard() }
        } else {
            item {
                Text(
                    stringResource(Res.string.paywall_plans_header),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            item {
                PlanCard(
                    icon = Icons.Rounded.CalendarMonth,
                    title = stringResource(Res.string.paywall_monthly_title),
                    price = stringResource(Res.string.paywall_monthly_price),
                    ctaLabel = stringResource(Res.string.paywall_cta_subscribe),
                    isLoading = state.purchasing == PurchaseKind.SUB_MONTHLY,
                    onClick = { onIntent(PaywallIntent.Purchase(PurchaseKind.SUB_MONTHLY)) },
                )
            }
            item {
                PlanCard(
                    icon = Icons.Rounded.EventRepeat,
                    title = stringResource(Res.string.paywall_yearly_title),
                    price = stringResource(Res.string.paywall_yearly_price),
                    ctaLabel = stringResource(Res.string.paywall_cta_subscribe),
                    isLoading = state.purchasing == PurchaseKind.SUB_YEARLY,
                    onClick = { onIntent(PaywallIntent.Purchase(PurchaseKind.SUB_YEARLY)) },
                )
            }
            item {
                HeroPlanCard(
                    title = stringResource(Res.string.paywall_lifetime_title),
                    price = stringResource(Res.string.paywall_lifetime_price),
                    badge = stringResource(Res.string.paywall_lifetime_badge),
                    ctaLabel = stringResource(Res.string.paywall_cta_buy),
                    isLoading = state.purchasing == PurchaseKind.LIFETIME,
                    onClick = { onIntent(PaywallIntent.Purchase(PurchaseKind.LIFETIME)) },
                )
            }
        }

        item {
            TextButton(onClick = { onIntent(PaywallIntent.Restore) }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(Res.string.paywall_restore))
            }
        }
    }
}

@Composable
private fun AlreadyPremiumCard() {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.CheckCircle)
            Spacer(Modifier.size(16.dp))
            Text(stringResource(Res.string.paywall_already_premium), style = MaterialTheme.typography.titleMedium)
        }
    }
}

@Composable
private fun PlanCard(
    icon: ImageVector,
    title: String,
    price: String,
    ctaLabel: String,
    isLoading: Boolean,
    onClick: () -> Unit,
) {
    Card(
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(modifier = Modifier.fillMaxWidth().padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
            IconBadge(icon)
            Spacer(Modifier.size(16.dp))
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(
                    price,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.size(12.dp))
            Button(onClick = onClick, enabled = !isLoading) {
                if (isLoading) {
                    CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
                } else {
                    Text(ctaLabel)
                }
            }
        }
    }
}

/** The recommended tier — a gradient background rather than the plain surface every other card uses. */
@Composable
private fun HeroPlanCard(
    title: String,
    price: String,
    badge: String,
    ctaLabel: String,
    isLoading: Boolean,
    onClick: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(MaterialTheme.shapes.large)
            .background(Brush.linearGradient(listOf(Color(0xFF7C5CFF), Color(0xFF4C7BFF))))
            .padding(16.dp),
    ) {
        Column {
            Surface(shape = MaterialTheme.shapes.small, color = Color.White.copy(alpha = 0.2f)) {
                Text(
                    badge,
                    style = MaterialTheme.typography.labelSmall,
                    color = Color.White,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                )
            }
            Spacer(Modifier.size(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        title,
                        style = MaterialTheme.typography.titleMedium,
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(price, style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.85f))
                }
                Spacer(Modifier.size(12.dp))
                Button(
                    onClick = onClick,
                    enabled = !isLoading,
                    colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = Color(0xFF4C7BFF)),
                ) {
                    if (isLoading) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                            color = Color(0xFF4C7BFF),
                        )
                    } else {
                        Text(ctaLabel)
                    }
                }
            }
        }
    }
}

/** A colour-lit icon badge matching the settings screen's row icons. */
@Composable
private fun IconBadge(icon: ImageVector) {
    Box(
        modifier = Modifier
            .size(44.dp)
            .clip(MaterialTheme.shapes.medium)
            .background(MaterialTheme.colorScheme.primary.copy(alpha = 0.16f)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
    }
}
