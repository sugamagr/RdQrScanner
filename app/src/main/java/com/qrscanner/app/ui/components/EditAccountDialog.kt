package com.qrscanner.app.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import com.qrscanner.app.data.RdAccount
import com.qrscanner.app.ui.theme.AccentMint
import com.qrscanner.app.ui.theme.ErrorRed
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.SurfaceWhite
import com.qrscanner.app.ui.theme.TextPrimary
import com.qrscanner.app.ui.theme.TextSecondary

/**
 * Edit dialog for a MANUAL [RdAccount]. RD number is locked (PK
 * change would require delete-then-recreate). Name + monthlyAmount +
 * isActive are mutable.
 *
 * CSV-sourced rows never reach this dialog — the locked-row Snackbar
 * intercepts the edit attempt upstream. Verified by the Accounts
 * screen branch that picks edit-vs-locked-snackbar by source.
 */
@Composable
fun EditAccountDialog(
    account: RdAccount,
    onDismiss: () -> Unit,
    onSave: (name: String, monthlyAmount: Int, isActive: Boolean) -> Unit
) {
    var name by remember(account.rdNumber) { mutableStateOf(account.name) }
    var amount by remember(account.rdNumber) { mutableStateOf(account.monthlyAmount.toString()) }
    var isActive by remember(account.rdNumber) { mutableStateOf(account.isActive) }

    val trimmedName = name.trim()
    val parsedAmount = amount.trim().toIntOrNull()
    val nameValid = trimmedName.isNotEmpty() && trimmedName.length <= 60
    val amountValid = parsedAmount != null && parsedAmount > 0
    val canSave = nameValid && amountValid &&
        (trimmedName != account.name || parsedAmount != account.monthlyAmount || isActive != account.isActive)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Column {
                Text(
                    "Edit account",
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
                Text(
                    "RD #${account.rdNumber}",
                    style = MaterialTheme.typography.labelMedium.copy(fontFamily = FontFamily.Monospace),
                    color = TextSecondary
                )
            }
        },
        text = {
            Column {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Customer name") },
                    isError = name.isNotBlank() && !nameValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        capitalization = androidx.compose.ui.text.input.KeyboardCapitalization.Words,
                        imeAction = ImeAction.Next
                    ),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryOrange,
                        focusedContainerColor = SurfaceWhite,
                        unfocusedContainerColor = SurfaceWhite
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(10.dp))
                OutlinedTextField(
                    value = amount,
                    onValueChange = { amount = it.filter(Char::isDigit) },
                    label = { Text("Monthly amount (INR)") },
                    isError = amount.isNotBlank() && !amountValid,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Number,
                        imeAction = ImeAction.Done
                    ),
                    shape = RoundedCornerShape(10.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryOrange,
                        focusedContainerColor = SurfaceWhite,
                        unfocusedContainerColor = SurfaceWhite
                    ),
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(14.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 4.dp)
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            "Active",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                            color = TextPrimary
                        )
                        Text(
                            if (isActive) "Visible on the default Accounts list"
                            else "Hidden until toggled on or scanned",
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                    Switch(
                        checked = isActive,
                        onCheckedChange = { isActive = it },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = SurfaceWhite,
                            checkedTrackColor = AccentMint,
                            uncheckedThumbColor = SurfaceWhite,
                            uncheckedTrackColor = TextSecondary.copy(alpha = 0.4f)
                        )
                    )
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    onSave(trimmedName, parsedAmount ?: account.monthlyAmount, isActive)
                },
                enabled = canSave,
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryOrange,
                    disabledContainerColor = TextSecondary.copy(alpha = 0.2f)
                ),
                shape = RoundedCornerShape(10.dp)
            ) {
                Text("Save", fontWeight = FontWeight.SemiBold)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel", color = TextSecondary)
            }
        },
        shape = RoundedCornerShape(20.dp),
        containerColor = SurfaceWhite,
        iconContentColor = ErrorRed
    )
}
