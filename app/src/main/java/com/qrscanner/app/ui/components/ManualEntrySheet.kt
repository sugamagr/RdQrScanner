package com.qrscanner.app.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PersonAddAlt1
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.SheetState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.unit.dp
import com.qrscanner.app.data.RdAccount
import com.qrscanner.app.ui.theme.CardBackground
import com.qrscanner.app.ui.theme.PrimaryOrange
import com.qrscanner.app.ui.theme.SurfaceWhite
import com.qrscanner.app.ui.theme.TextSecondary
import com.qrscanner.app.ui.theme.TextTertiary
import kotlinx.coroutines.delay

/**
 * Bottom sheet for adding an RD number to the current LOT without a
 * QR scan. Covers two operator scenarios that a scan can't:
 *
 *  1. The customer's account IS registered locally but their QR
 *     sticker is missing / illegible / faded. Operator searches by
 *     RD number or customer name, taps the match, and the RD lands
 *     in the current LOT exactly as if the QR had been scanned.
 *
 *  2. The customer's account is BRAND NEW (IndiaPost prints QR
 *     stickers only after overnight maturity, so a fresh account
 *     has no sticker on day one). Operator either types the RD
 *     into the search box first and hits the empty-state "add new"
 *     button, or bypasses search entirely with the top-level
 *     "Add new account" button. Either way lands in
 *     RegisterAccountDialog with editableRdNumber = true.
 *
 * WHY the top-level Add-new button is ALWAYS visible (not only in
 * empty-state): the operator carrying paper records for a fresh
 * account has no need to type-then-search-then-fail. Requiring them
 * to first type garbage-that-doesn't-match to trigger the empty
 * state would be a slow-UX trap.
 *
 * Contract:
 *  - onDismiss: sheet dragged/tapped closed. No state change.
 *  - onSelectExisting(rdNumber): operator picked a match. Caller
 *    MUST route this through the SAME scan-pipeline entry point
 *    (pendingValueRef + scanTrigger++) so every downstream constraint
 *    (dup check, LOT pin, monthly-amount cache, feedback tone) fires
 *    identically. NOT calling database insert directly from here.
 *  - onRegisterNew(seed): operator wants to create a new account.
 *    seed is either the operator's typed search value (digits only
 *    if numeric) or the empty string if they hit the top-level Add.
 *    Caller opens RegisterAccountDialog with editableRdNumber=true.
 *
 * Search: client-side filter on RdAccountDao.observeAll() output.
 * At 200 accounts × O(1) contains per keystroke × 2 fields =
 * ~400 comparisons per keystroke, well under 1ms on Pixel 4a.
 * NOT using a Room LIKE query because live-filter on a snapshot
 * beats a debounced network round-trip when the entire dataset
 * fits in memory (which it does at this scale).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ManualEntrySheet(
    accounts: List<RdAccount>,
    sheetState: SheetState,
    onDismiss: () -> Unit,
    onSelectExisting: (rdNumber: String) -> Unit,
    onRegisterNew: (seed: String) -> Unit
) {
    var query by remember { mutableStateOf("") }
    var debouncedQuery by remember { mutableStateOf("") }

    LaunchedEffect(query) {
        delay(200)
        debouncedQuery = query.trim()
    }

    // P3 SEMANTIC: read debouncedQuery INSIDE the derivedStateOf blocks
    // so Compose tracks the State read and re-invalidates on every
    // keystroke. An earlier attempt captured `val trimmed = debouncedQuery`
    // at the top of the function and used that variable inside the
    // derivedStateOf. That captured the state value ONCE at composition
    // start; subsequent recompositions saw the same stale value because
    // the derivedStateOf lambda's captured reference didn't re-read the
    // MutableState. Compose only tracks state reads that happen inside
    // the tracked scope (derivedStateOf / composables / snapshotFlow).
    val isDigitsOnly by remember {
        derivedStateOf {
            val q = debouncedQuery
            q.isNotEmpty() && q.all { it.isDigit() }
        }
    }
    val filtered by remember(accounts) {
        derivedStateOf {
            val q = debouncedQuery
            if (q.isEmpty()) return@derivedStateOf accounts
            val lower = q.lowercase()
            accounts.filter { acc ->
                acc.rdNumber.contains(q) ||
                    acc.name.lowercase().contains(lower)
            }
        }
    }

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceWhite,
        contentWindowInsets = { WindowInsets.navigationBars }
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 20.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .background(PrimaryOrange.copy(alpha = 0.12f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.PersonAddAlt1,
                        contentDescription = null,
                        tint = PrimaryOrange,
                        modifier = Modifier.size(20.dp)
                    )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Add without scanning",
                        style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = "Find an account or register a new one",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }
                IconButton(onClick = onDismiss) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = TextTertiary
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            OutlinedTextField(
                value = query,
                onValueChange = { input ->
                    query = input
                },
                placeholder = { Text("Search by name or number") },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        tint = PrimaryOrange
                    )
                },
                trailingIcon = if (query.isNotEmpty()) {
                    {
                        IconButton(onClick = { query = "" }) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Clear",
                                tint = TextTertiary
                            )
                        }
                    }
                } else null,
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = if (isDigitsOnly) KeyboardType.Number else KeyboardType.Text
                ),
                modifier = Modifier.fillMaxWidth(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = PrimaryOrange,
                    unfocusedBorderColor = PrimaryOrange
                )
            )

            Spacer(modifier = Modifier.height(12.dp))

            Button(
                onClick = { onRegisterNew(if (isDigitsOnly) debouncedQuery else "") },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = PrimaryOrange.copy(alpha = 0.12f),
                    contentColor = PrimaryOrange
                ),
                shape = RoundedCornerShape(12.dp)
            ) {
                Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(20.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Add new account", fontWeight = FontWeight.SemiBold, maxLines = 1)
            }

            Spacer(modifier = Modifier.height(16.dp))

            if (debouncedQuery.isNotEmpty() && filtered.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = CardBackground
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No matches for \"$debouncedQuery\"",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap \"Add new account\" above to register this one",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            } else if (accounts.isEmpty()) {
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(16.dp),
                    color = CardBackground
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "No accounts registered yet",
                            style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold)
                        )
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = "Tap \"Add new account\" above to register your first",
                            style = MaterialTheme.typography.bodySmall,
                            color = TextSecondary
                        )
                    }
                }
            } else {
                val label = if (debouncedQuery.isEmpty()) {
                    "All accounts (${filtered.size})"
                } else {
                    "${filtered.size} match${if (filtered.size == 1) "" else "es"}"
                }
                Text(
                    text = label,
                    style = MaterialTheme.typography.labelMedium,
                    color = TextSecondary
                )
                Spacer(modifier = Modifier.height(8.dp))

                LazyColumn(
                    state = rememberLazyListState(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(bottom = 8.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(max = 400.dp)
                ) {
                    items(filtered, key = { it.rdNumber }) { account ->
                        AccountResultRow(
                            account = account,
                            onClick = { onSelectExisting(account.rdNumber) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun AccountResultRow(
    account: RdAccount,
    onClick: () -> Unit
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        color = CardBackground
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = account.name.ifBlank { "Unnamed" },
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = account.rdNumber,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                    color = TextSecondary,
                    maxLines = 1
                )
            }
            Text(
                text = "\u20B9${account.monthlyAmount}",
                style = MaterialTheme.typography.bodyLarge.copy(
                    fontWeight = FontWeight.Bold,
                    color = PrimaryOrange
                )
            )
        }
    }
}
