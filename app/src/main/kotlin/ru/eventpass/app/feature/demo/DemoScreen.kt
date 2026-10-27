package ru.eventpass.app.feature.demo

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import ru.eventpass.app.R

@Composable
fun DemoRoute(recordId: String, onClose: () -> Unit) {
    val viewModel = hiltViewModel<DemoViewModel, DemoViewModel.Factory>(
        creationCallback = { factory -> factory.create(recordId) },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    DemoScreen(state, viewModel::increment, onClose)
}

@Composable
fun DemoScreen(state: DemoUiState, onIncrement: () -> Unit, onClose: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(20.dp),
    ) {
        Text(stringResource(R.string.demo_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(R.string.demo_description))
        when (state) {
            DemoUiState.Loading -> {
                CircularProgressIndicator(Modifier.testTag("demo-loading"))
                Text(stringResource(R.string.loading))
            }
            DemoUiState.NotFound -> Text(stringResource(R.string.demo_not_found))
            is DemoUiState.Content -> {
                Text(stringResource(R.string.demo_seed, state.seed))
                Text(
                    stringResource(R.string.demo_count, state.count),
                    modifier = Modifier.testTag("demo-count"),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Button(onClick = onIncrement, modifier = Modifier.testTag("increment")) {
                    Text(stringResource(R.string.increment))
                }
            }
        }
        TextButton(onClick = onClose, modifier = Modifier.testTag("close-demo")) {
            Text(stringResource(R.string.close_demo))
        }
    }
}
