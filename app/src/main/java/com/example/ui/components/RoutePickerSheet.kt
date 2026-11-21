package com.example.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CellTower
import androidx.compose.material.icons.rounded.SimCard
import androidx.compose.material.icons.rounded.WifiCalling3
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.domain.model.ResolvedOutgoingRoute
import com.example.domain.model.SipRegistrationStatus
import com.example.ui.theme.SignalConnected
import com.example.ui.theme.SignalWarning

@Composable
fun SipStatusPill(
    status: SipRegistrationStatus?,
    transportLabel: String?,
    balanceText: String? = null,
    showBalance: Boolean = false,
    modifier: Modifier = Modifier
) {
    val dotColor = when (status) {
        SipRegistrationStatus.REGISTERED -> SignalConnected
        SipRegistrationStatus.REGISTERING,
        SipRegistrationStatus.DISCOVERING -> SignalWarning
        SipRegistrationStatus.STANDBY -> MaterialTheme.colorScheme.primary.copy(alpha = 0.75f)
        SipRegistrationStatus.ERROR -> MaterialTheme.colorScheme.error
        SipRegistrationStatus.UNREGISTERED -> MaterialTheme.colorScheme.outline
        null -> MaterialTheme.colorScheme.outline
    }

    val text = when {
        status != null && showBalance && !balanceText.isNullOrBlank() -> "SIP • $balanceText"
        status == SipRegistrationStatus.REGISTERED -> "SIP • ${transportLabel ?: "OK"}"
        status == SipRegistrationStatus.REGISTERING || status == SipRegistrationStatus.DISCOVERING -> "SIP • Connecting"
        status == SipRegistrationStatus.STANDBY -> "SIP • Outbound"
        status == SipRegistrationStatus.ERROR -> "SIP • Error"
        status == SipRegistrationStatus.UNREGISTERED -> "SIP • Offline"
        else -> "No SIP"
    }

    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(50),
        color = MaterialTheme.colorScheme.surfaceVariant
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(8.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            Text(
                text = text,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = if (showBalance && !balanceText.isNullOrBlank()) FontWeight.SemiBold else FontWeight.Normal,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OutgoingRoutePickerSheet(
    destination: String,
    routes: List<ResolvedOutgoingRoute>,
    onSelectRoute: (ResolvedOutgoingRoute) -> Unit,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = MaterialTheme.colorScheme.surface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                text = "Call with",
                style = MaterialTheme.typography.titleLarge
            )
            Text(
                text = destination,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Spacer(modifier = Modifier.height(4.dp))

            routes.forEachIndexed { index, route ->
                when (route) {
                    is ResolvedOutgoingRoute.CellularSim -> {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onSelectRoute(route) }
                                .testTag("route_picker_sim_${route.sim.slotIndex + 1}"),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.primaryContainer),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.SimCard,
                                        contentDescription = route.sim.shortLabel,
                                        tint = MaterialTheme.colorScheme.onPrimaryContainer
                                    )
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column {
                                    Text(
                                        text = route.sim.shortLabel,
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = route.sim.carrierName.ifBlank { "Cellular Network" },
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }

                    is ResolvedOutgoingRoute.SipAccount -> {
                        Surface(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(16.dp))
                                .clickable { onSelectRoute(route) }
                                .testTag("route_picker_sip_$index"),
                            color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(16.dp)
                        ) {
                            Row(
                                modifier = Modifier.padding(16.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Box(
                                    modifier = Modifier
                                        .size(44.dp)
                                        .clip(CircleShape)
                                        .background(MaterialTheme.colorScheme.tertiary),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Icon(
                                        imageVector = Icons.Rounded.WifiCalling3,
                                        contentDescription = "SIP VoIP",
                                        tint = MaterialTheme.colorScheme.onTertiary
                                    )
                                }
                                Spacer(modifier = Modifier.width(14.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = "SIP VoIP • ${route.account.displayName}",
                                        style = MaterialTheme.typography.titleMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                    Text(
                                        text = "${route.account.sipUri} (${route.account.transport.label}:${route.account.port})",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                                Icon(
                                    imageVector = Icons.Rounded.CellTower,
                                    contentDescription = route.account.status.label,
                                    tint = when (route.account.status) {
                                        SipRegistrationStatus.REGISTERED -> SignalConnected
                                        SipRegistrationStatus.STANDBY -> MaterialTheme.colorScheme.primary
                                        else -> MaterialTheme.colorScheme.onSurfaceVariant
                                    },
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
