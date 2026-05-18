package com.amurcanov.tgwsproxy

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog

@Composable
fun OnboardingDialog(
    onComplete: (dontShowAgain: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    val pages = listOf(
        stringResource(R.string.onboarding_page1),
        stringResource(R.string.onboarding_page2),
        stringResource(R.string.onboarding_page3),
        stringResource(R.string.onboarding_page4),
        stringResource(R.string.onboarding_page5),
    )
    var page by remember { mutableIntStateOf(0) }
    var dontShowAgain by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(shape = RoundedCornerShape(24.dp)) {
            Column(
                modifier = Modifier
                    .padding(20.dp)
                    .heightIn(max = 520.dp),
            ) {
                Text(
                    stringResource(R.string.onboarding_title),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.onboarding_step, page + 1, pages.size),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    pages[page],
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState()),
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    TextButton(onClick = { dontShowAgain = !dontShowAgain }) {
                        Text(
                            if (dontShowAgain) {
                                stringResource(R.string.onboarding_dont_show_checked)
                            } else {
                                stringResource(R.string.onboarding_dont_show)
                            },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.End,
                ) {
                    if (page > 0) {
                        TextButton(onClick = { page -= 1 }) {
                            Text(stringResource(R.string.onboarding_back))
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.onboarding_skip))
                    }
                    TextButton(
                        onClick = {
                            if (page < pages.lastIndex) {
                                page += 1
                            } else {
                                onComplete(dontShowAgain)
                            }
                        },
                    ) {
                        Text(
                            if (page < pages.lastIndex) {
                                stringResource(R.string.onboarding_next)
                            } else {
                                stringResource(R.string.onboarding_done)
                            },
                        )
                    }
                }
            }
        }
    }
}
