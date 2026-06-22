package com.qrscanner.app.ui.components

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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qrscanner.app.R
import com.qrscanner.app.data.RdAccount
import com.qrscanner.app.ui.theme.AccentMint
import com.qrscanner.app.ui.theme.ErrorRed
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.SurfaceWhite
import com.qrscanner.app.ui.theme.TextPrimary
import com.qrscanner.app.ui.theme.TextSecondary
import com.qrscanner.app.ui.theme.WarningAmber
import com.qrscanner.app.util.MonthYear

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
    onSave: (name: String, monthlyAmount: Int, isActive: Boolean, paidTill: PaidTillEdit) -> Unit
) {
    var name by remember(account.rdNumber) { mutableStateOf(account.name) }
    var amount by remember(account.rdNumber) { mutableStateOf(account.monthlyAmount.toString()) }
    var isActive by remember(account.rdNumber) { mutableStateOf(account.isActive) }

    val originalPaidTill = remember(account.rdNumber) {
        account.lastPaidThrough?.let { MonthYear.parseToken(it) }
    }
    var paidTillSelection by remember(account.rdNumber) { mutableStateOf(originalPaidTill) }
    var paidTillCleared by remember(account.rdNumber) { mutableStateOf(false) }
    var showPicker by remember { mutableStateOf(false) }
    var pendingBackwardPick by remember { mutableStateOf<MonthYear?>(null) }
    var pendingClearConfirm by remember { mutableStateOf(false) }

    val paidTillChanged = paidTillCleared || paidTillSelection != originalPaidTill

    val trimmedName = name.trim()
    val parsedAmount = amount.trim().toIntOrNull()
    val nameValid = trimmedName.isNotEmpty() && trimmedName.length <= 60
    val amountValid = parsedAmount != null && parsedAmount > 0
    val canSave = nameValid && amountValid &&
        (trimmedName != account.name || parsedAmount != account.monthlyAmount ||
            isActive != account.isActive || paidTillChanged)

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

                Spacer(modifier = Modifier.height(14.dp))
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(SurfaceWhite, RoundedCornerShape(10.dp))
                        .clickable { showPicker = true }
                        .padding(horizontal = 12.dp, vertical = 12.dp)
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = stringResource(R.string.account_paid_till_label),
                                style = MaterialTheme.typography.labelSmall.copy(color = TextSecondary)
                            )
                            Text(
                                text = when {
                                    paidTillCleared -> stringResource(R.string.account_paid_till_never)
                                    paidTillSelection != null -> paidTillSelection!!.formatShort()
                                    else -> stringResource(R.string.account_paid_till_never)
                                },
                                style = MaterialTheme.typography.bodyMedium.copy(
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary
                                )
                            )
                        }
                        Text(
                            text = stringResource(R.string.account_paid_till_pick),
                            style = MaterialTheme.typography.labelMedium.copy(
                                color = PrimaryOrange,
                                fontWeight = FontWeight.SemiBold
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = {
                    val paidTillEdit = when {
                        !paidTillChanged -> PaidTillEdit.Unchanged
                        paidTillCleared -> PaidTillEdit.Cleared
                        paidTillSelection != null ->
                            PaidTillEdit.SetTo(paidTillSelection!!, isRegression = isRegression(originalPaidTill, paidTillSelection))
                        else -> PaidTillEdit.Unchanged
                    }
                    onSave(trimmedName, parsedAmount ?: account.monthlyAmount, isActive, paidTillEdit)
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

    if (showPicker) {
        val disabledMonths = remember(originalPaidTill, paidTillSelection) {
            // Only disable the literal current selection so the picker
            // doesn't show its own state as a "tap to keep" option.
            (paidTillSelection ?: originalPaidTill)?.let { setOf(it) } ?: emptySet()
        }
        val initial = paidTillSelection ?: originalPaidTill ?: MonthYear.current()
        MonthPickerDialog(
            initialSelection = initial,
            disabledMonths = disabledMonths,
            onDismiss = { showPicker = false },
            onPick = { picked ->
                showPicker = false
                val regress = isRegression(originalPaidTill, picked)
                if (regress) {
                    pendingBackwardPick = picked
                } else {
                    paidTillSelection = picked
                    paidTillCleared = false
                }
            },
            allowClear = true,
            onClear = {
                showPicker = false
                pendingClearConfirm = true
            }
        )
    }

    pendingBackwardPick?.let { picked ->
        ConfirmModal(
            title = stringResource(R.string.account_paid_till_backward_title),
            body = stringResource(
                R.string.account_paid_till_backward_body,
                account.name,
                originalPaidTill?.formatShort() ?: "—",
                picked.formatShort()
            ),
            confirmLabel = "Confirm",
            confirmIsWarning = true,
            onCancel = { pendingBackwardPick = null },
            onConfirm = {
                paidTillSelection = picked
                paidTillCleared = false
                pendingBackwardPick = null
            }
        )
    }

    if (pendingClearConfirm) {
        ConfirmModal(
            title = stringResource(R.string.account_paid_till_clear_title),
            body = stringResource(R.string.account_paid_till_clear_body, account.name),
            confirmLabel = "Clear",
            confirmIsWarning = true,
            onCancel = { pendingClearConfirm = false },
            onConfirm = {
                paidTillCleared = true
                paidTillSelection = null
                pendingClearConfirm = false
            }
        )
    }
}

/** Operator-driven Paid till state delta produced by [EditAccountDialog]. */
sealed class PaidTillEdit {
    /** No change to the account's lastPaidThrough. */
    data object Unchanged : PaidTillEdit()
    /** Set lastPaidThrough to NULL. */
    data object Cleared : PaidTillEdit()
    /**
     * Set lastPaidThrough to [newValue]. [isRegression] is true when the
     * new value sits at or before the original — the caller must use
     * setLastPaidThroughExplicit (no monotonic guard) instead of
     * updateLastPaidThroughMonotonic for those cases.
     */
    data class SetTo(val newValue: MonthYear, val isRegression: Boolean) : PaidTillEdit()
}

private fun isRegression(original: MonthYear?, candidate: MonthYear?): Boolean {
    if (original == null || candidate == null) return false
    return candidate <= original
}

@Composable
private fun ConfirmModal(
    title: String,
    body: String,
    confirmLabel: String,
    confirmIsWarning: Boolean,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Dialog(
        onDismissRequest = onCancel,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        androidx.compose.material3.Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            shape = RoundedCornerShape(20.dp),
            color = SurfaceWhite,
            tonalElevation = 6.dp
        ) {
            Column(modifier = Modifier.padding(20.dp)) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                    color = TextPrimary
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = body,
                    style = MaterialTheme.typography.bodySmall.copy(color = TextSecondary)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    TextButton(onClick = onCancel, modifier = Modifier.weight(1f)) {
                        Text("Cancel", color = TextSecondary, fontWeight = FontWeight.SemiBold)
                    }
                    Button(
                        onClick = onConfirm,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (confirmIsWarning) WarningAmber else PrimaryOrange
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(confirmLabel, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
        }
    }
}
