package com.qrscanner.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.qrscanner.app.ui.theme.ErrorRed
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.SurfaceWhite
import com.qrscanner.app.ui.theme.TextPrimary
import com.qrscanner.app.ui.theme.TextSecondary

/**
 * The two-path delete confirmation modal. Copy verbatim from user spec:
 *
 *   Mark inactive (recommended) — the account stays in the system, you
 *   can re-activate it any time, and all past payment history is
 *   preserved. Pick this when an account closes naturally.
 *
 *   Delete — wipes the account profile entirely from this phone and the
 *   portal. Pick this only if you added the account by mistake and
 *   want it gone like it never existed.
 *
 * Mark Inactive is the primary action (PrimaryOrange filled button);
 * Delete is a danger TextButton (ErrorRed) to discourage misclick.
 */
@Composable
fun DeleteOrInactivateDialog(
    accountName: String,
    rdNumber: String,
    onCancel: () -> Unit,
    onMarkInactive: () -> Unit,
    onDelete: () -> Unit
) {
    val haptics = LocalHapticFeedback.current
    AlertDialog(
        onDismissRequest = onCancel,
        title = {
            Column {
                Text(
                    "Mark inactive or delete?",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    "$accountName  ·  RD #$rdNumber",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = TextSecondary
                )
            }
        },
        text = {
            Column {
                Text(
                    "Mark inactive (recommended) — the account stays in the system, " +
                        "you can re-activate it any time, and all past payment history is " +
                        "preserved. Pick this when an account closes naturally.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(10.dp))
                Text(
                    "Delete — wipes the account profile entirely from this phone and " +
                        "the portal. Pick this only if you added the account by mistake " +
                        "and want it gone like it never existed.",
                    style = MaterialTheme.typography.bodySmall,
                    color = TextSecondary
                )
            }
        },
        // 3 actions don't fit AlertDialog's confirm+dismiss row on
        // 320dp screens. Stacking the primary Mark Inactive at full
        // width with a secondary Cancel/Delete row below works on
        // every phone width and reinforces the recommended action.
        confirmButton = {
            Column(modifier = Modifier.fillMaxWidth()) {
                Button(
                    onClick = onMarkInactive,
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryOrange),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Mark Inactive", fontWeight = FontWeight.SemiBold)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End
                ) {
                    TextButton(onClick = onCancel) {
                        Text("Cancel", color = TextSecondary)
                    }
                    TextButton(onClick = {
                        haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                        onDelete()
                    }) {
                        Text("Delete", color = ErrorRed, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        },
        dismissButton = null,
        shape = RoundedCornerShape(20.dp),
        containerColor = SurfaceWhite
    )
}
