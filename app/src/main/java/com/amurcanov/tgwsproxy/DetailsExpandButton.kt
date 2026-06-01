package com.amurcanov.tgwsproxy

import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource

@Composable
fun DetailsExpandButton(
    expanded: Boolean,
    onExpandedChange: (Boolean) -> Unit,
) {
    TextButton(onClick = { onExpandedChange(!expanded) }) {
        Text(
            if (expanded) {
                stringResource(R.string.settings_hide_details)
            } else {
                stringResource(R.string.settings_show_details)
            },
        )
    }
}
