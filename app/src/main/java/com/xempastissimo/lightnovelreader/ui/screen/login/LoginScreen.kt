package com.xempastissimo.lightnovelreader.ui.screen.login

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.xempastissimo.lightnovelreader.data.source.BookSource
import com.xempastissimo.lightnovelreader.data.source.LoginResult
import com.xempastissimo.lightnovelreader.ui.AppContainer
import com.xempastissimo.lightnovelreader.ui.AppViewModelFactory
import com.xempastissimo.lightnovelreader.ui.LocalAppContainer
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class LoginUiState(
    val userName: String = "",
    val password: String = "",
    val keepLoggedIn: Boolean = false,
    val submitting: Boolean = false,
    val error: String? = null,
    val success: Boolean = false,
)

class LoginViewModel(private val source: BookSource) : ViewModel() {

    private val _state = MutableStateFlow(LoginUiState())
    val state: StateFlow<LoginUiState> = _state.asStateFlow()

    fun onUserNameChange(value: String) = _state.update { it.copy(userName = value, error = null) }

    fun onPasswordChange(value: String) = _state.update { it.copy(password = value, error = null) }

    fun onKeepLoggedInChange(value: Boolean) = _state.update { it.copy(keepLoggedIn = value) }

    fun submit() {
        val current = _state.value
        if (current.userName.isBlank() || current.password.isEmpty()) {
            _state.update { it.copy(error = "请填写用户名与密码") }
            return
        }
        _state.update { it.copy(submitting = true, error = null) }
        viewModelScope.launch {
            // The password is used for this request only and is never persisted.
            val result = source.login(
                userName = current.userName.trim(),
                password = current.password,
                keepDays = if (current.keepLoggedIn) 31 else 0,
            )
            when (result) {
                is LoginResult.Success -> {
                    _state.update { it.copy(submitting = false, success = true, password = "") }
                }

                is LoginResult.NeedCaptcha -> {
                    _state.update {
                        it.copy(
                            submitting = false,
                            error = "该站点要求输入验证码。请先在浏览器中登录一次，或稍后重试。",
                        )
                    }
                }

                is LoginResult.Failure -> {
                    _state.update { it.copy(submitting = false, error = result.message) }
                }
            }
        }
    }

    companion object {
        fun factory(container: AppContainer) = AppViewModelFactory<LoginViewModel> {
            LoginViewModel(it.bookSource)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LoginScreen(
    onLoggedIn: () -> Unit,
    onOpenWebLogin: () -> Unit,
    viewModel: LoginViewModel = viewModel(factory = LoginViewModel.factory(LocalAppContainer.current)),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var passwordVisible by remember { mutableStateOf(false) }

    if (state.success) {
        androidx.compose.runtime.LaunchedEffect(Unit) { onLoggedIn() }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("登录书源账号") },
            navigationIcon = {
                IconButton(onClick = onLoggedIn) {
                    Icon(Icons.Filled.ArrowBack, contentDescription = "返回")
                }
            },
        )

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "账号仅用于访问你本人在该站点的书架、榜单与搜索，密码只用于本次登录请求，不会保存在本机。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Text(
                text = "若站点要求人机校验（Cloudflare），原生表单会被拦截，请改用下方的浏览器登录。",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.tertiary,
            )

            Button(
                onClick = onOpenWebLogin,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("使用浏览器登录（推荐，可完成人机校验）")
            }

            OutlinedTextField(
                value = state.userName,
                onValueChange = viewModel::onUserNameChange,
                label = { Text("用户名或邮箱") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            )

            OutlinedTextField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = { Text("密码") },
                singleLine = true,
                visualTransformation = if (passwordVisible) {
                    androidx.compose.ui.text.input.VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                modifier = Modifier.fillMaxWidth(),
            )

            TextButton(onClick = { passwordVisible = !passwordVisible }) {
                Text(if (passwordVisible) "隐藏密码" else "显示密码")
            }

            androidx.compose.foundation.layout.Row(
                verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
            ) {
                androidx.compose.material3.Switch(
                    checked = state.keepLoggedIn,
                    onCheckedChange = viewModel::onKeepLoggedInChange,
                )
                Text(
                    text = "保持登录（约 30 天，仅保存站点会话 Cookie）",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }

            state.error?.let { message ->
                Text(
                    text = message,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }

            Button(
                onClick = viewModel::submit,
                enabled = !state.submitting,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.submitting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Text("登录中…")
                } else {
                    Text("登录")
                }
            }
        }
    }
}
