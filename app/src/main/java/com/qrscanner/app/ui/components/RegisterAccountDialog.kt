package com.qrscanner.app.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAddAlt1
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.SurfaceWhite
import com.qrscanner.app.ui.theme.TextSecondary
import com.qrscanner.app.ui.theme.TextTertiary

/**
 * Dual-mode "Register this account" dialog.
 *
 * Two entry paths share this composable so the visual language stays
 * consistent and the validation rules can't drift between them:
 *
 *  1. Scanner scan-fail path (editableRdNumber = false)
 *     - Fires when a valid QR decodes to an RD number with no
 *       matching rd_accounts row.
 *     - rdNumber is the scanned value, shown READ-ONLY (operator
 *       already committed the barcode; retyping is a source of typos,
 *       not a feature).
 *     - onRegister carries THAT rdNumber back to the caller.
 *
 *  2. Manual-entry path (editableRdNumber = true)
 *     - Fires when the operator taps "Add new account" from
 *       ManualEntrySheet.
 *     - rdNumber may be empty; initialRdSeed prefills the field if
 *       the operator typed digits into the search box first.
 *     - Field is a numeric OutlinedTextField validated against
 *       isValidRdNumber (9-15 digits, matches CSV parser regex).
 *     - onRegister carries the TYPED rdNumber back to the caller.
 *
 * NOT splitting into two dialogs because:
 *   - Same fields (name, monthlyAmount), same validation ranges,
 *     same visual chrome (orange badge, PersonAddAlt1 icon, save
 *     button copy)
 *   - Two files would drift on the amount range or copy the moment
 *     someone edited one and forgot the other
 *   - Duplication risk is worse than a small if-else here
 *
 * Contract:
 *  - onDismiss: user backed out. Caller drops the flow. NOT inserted
 *    into any lot. Scanner path re-arms the camera; manual path
 *    returns to the ManualEntrySheet.
 *  - onRegister(rdNumber, name, monthlyAmount): user tapped Save
 *    with valid inputs. Caller inserts an rd_accounts row as
 *    source=MANUAL, isActive=true, then re-feeds the RD to the scan
 *    pipeline so the row lands in the current LOT on the same user
 *    gesture. This is the "one-tap register-and-record" invariant
 *    both entry paths depend on.
 *
 * Validation locked at input time so bad data never reaches Room:
 *  - rdNumber (editable mode only): matches Regex("^\d{9,15}$").
 *    Same regex as scanner isValidRdNumber and CSV parser rd_number
 *    field. Deliberately duplicated so a copy-paste of the accepted
 *    range doesn't require reaching across the file boundary.
 *  - name: non-blank after trim, <= 60 chars (matches CSV parser cap).
 *  - monthlyAmount: positive integer in [10, 100000]. Range chosen
 *    from user's actual DOP roster (\u20B950 min, \u20B910,000 max
 *    observed at 2026 ship) with 5\u00d7 upper headroom against typos.
 *    Rejecting at input keeps XlsxExporter column H math trustworthy.
 */
@Composable
fun RegisterAccountDialog(
    rdNumber: String,
    onDismiss: () -> Unit,
    onRegister: (rdNumber: String, name: String, monthlyAmount: Int) -> Unit,
    editableRdNumber: Boolean = false,
    initialRdSeed: String = ""
) {
    val seedFiltered = remember(initialRdSeed) {
        initialRdSeed.filter { it.isDigit() }.take(15)
    }
    var typedRdNumber by remember { mutableStateOf(seedFiltered) }
    var rdTouched by remember { mutableStateOf(seedFiltered.isNotEmpty()) }
    var name by remember { mutableStateOf("") }
    var amountText by remember { mutableStateOf("") }
    var nameTouched by remember { mutableStateOf(false) }
    var amountTouched by remember { mutableStateOf(false) }

    val effectiveRdNumber by remember {
        derivedStateOf { if (editableRdNumber) typedRdNumber.trim() else rdNumber }
    }
    val rdRegex = remember { Regex("^\\d{9,15}$") }
    val rdError by remember {
        derivedStateOf {
            if (!editableRdNumber) null else when {
                !rdTouched -> null
                typedRdNumber.isBlank() -> "RD number required"
                !rdRegex.matches(typedRdNumber.trim()) -> "9-15 digits"
                else -> null
            }
        }
    }

    val trimmedName by remember { derivedStateOf { name.trim() } }
    val nameError by remember {
        derivedStateOf {
            when {
                !nameTouched -> null
                trimmedName.isEmpty() -> "Name required"
                trimmedName.length > 60 -> "Too long (max 60)"
                else -> null
            }
        }
    }
    val parsedAmount by remember {
        derivedStateOf { amountText.trim().toIntOrNull() }
    }
    val amountError by remember {
        derivedStateOf {
            val v = parsedAmount
            when {
                !amountTouched -> null
                v == null -> "Numbers only"
                v < 10 -> "Min \u20B910"
                v > 100_000 -> "Max \u20B91,00,000"
                else -> null
            }
        }
    }
    val canSave by remember {
        derivedStateOf {
            val rdOk = if (editableRdNumber) rdRegex.matches(typedRdNumber.trim()) else true
            rdOk &&
                trimmedName.isNotEmpty() && trimmedName.length <= 60 &&
                (parsedAmount != null && parsedAmount!! in 10..100_000)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false
        )
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 28.dp),
            shape = RoundedCornerShape(24.dp),
            color = SurfaceWhite,
            tonalElevation = 8.dp
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .background(PrimaryOrange.copy(alpha = 0.12f), CircleShape),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PersonAddAlt1,
                            contentDescription = null,
                            tint = PrimaryOrange,
                            modifier = Modifier.size(22.dp)
                        )
                    }
                    Spacer(modifier = Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "New account",
                            style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                        )
                        Text(
                            text = "This RD isn't registered yet",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                    TextButton(onClick = onDismiss) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cancel and drop scan",
                            tint = TextTertiary
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                if (editableRdNumber) {
                    OutlinedTextField(
                        value = typedRdNumber,
                        onValueChange = { input ->
                            typedRdNumber = input.filter { it.isDigit() }.take(15)
                            rdTouched = true
                        },
                        label = { Text("RD number") },
                        placeholder = { Text("e.g. 123456789012") },
                        isError = rdError != null,
                        supportingText = rdError?.let { { Text(it) } }
                            ?: { Text("9 to 15 digits", color = TextTertiary) },
                        singleLine = true,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        modifier = Modifier.fillMaxWidth(),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = PrimaryOrange,
                            focusedLabelColor = PrimaryOrange
                        )
                    )
                } else {
                    Text(
                        text = "RD number",
                        style = MaterialTheme.typography.labelMedium,
                        color = TextSecondary
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = rdNumber,
                        style = MaterialTheme.typography.titleSmall.copy(
                            fontFamily = FontFamily.Monospace,
                            fontWeight = FontWeight.SemiBold
                        )
                    )
                }

                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { input ->
                        name = input
                        nameTouched = true
                    },
                    label = { Text("Customer name") },
                    placeholder = { Text("e.g. Ram Kumar") },
                    isError = nameError != null,
                    supportingText = nameError?.let { { Text(it) } },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryOrange,
                        focusedLabelColor = PrimaryOrange
                    )
                )

                Spacer(modifier = Modifier.height(12.dp))

                OutlinedTextField(
                    value = amountText,
                    onValueChange = { input ->
                        amountText = input.filter { it.isDigit() }
                        amountTouched = true
                    },
                    label = { Text("Monthly amount") },
                    placeholder = { Text("e.g. 500") },
                    leadingIcon = { Text("\u20B9", style = MaterialTheme.typography.titleMedium) },
                    isError = amountError != null,
                    supportingText = amountError?.let { { Text(it) } }
                        ?: { Text("Between \u20B910 and \u20B91,00,000", color = TextTertiary) },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = PrimaryOrange,
                        focusedLabelColor = PrimaryOrange
                    )
                )

                Spacer(modifier = Modifier.height(20.dp))

                Button(
                    onClick = {
                        val amt = parsedAmount ?: return@Button
                        val rd = effectiveRdNumber
                        if (rd.isBlank()) return@Button
                        onRegister(rd, trimmedName, amt)
                    },
                    enabled = canSave,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = PrimaryOrange),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(Icons.Default.PersonAddAlt1, contentDescription = null, modifier = Modifier.size(20.dp))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Save and continue scanning", fontWeight = FontWeight.SemiBold, maxLines = 1)
                }
            }
        }
    }
}
