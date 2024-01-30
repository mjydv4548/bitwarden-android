package com.x8bit.bitwarden.ui.platform.feature.settings.accountsecurity.loginapproval

import android.os.Parcelable
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import com.x8bit.bitwarden.R
import com.x8bit.bitwarden.data.auth.repository.AuthRepository
import com.x8bit.bitwarden.data.auth.repository.model.AuthRequestResult
import com.x8bit.bitwarden.ui.platform.base.BaseViewModel
import com.x8bit.bitwarden.ui.platform.base.util.Text
import com.x8bit.bitwarden.ui.platform.base.util.asText
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.parcelize.Parcelize
import java.time.format.DateTimeFormatter
import java.util.TimeZone
import javax.inject.Inject

private const val KEY_STATE = "state"

/**
 * View model for the login approval screen.
 */
@HiltViewModel
class LoginApprovalViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    savedStateHandle: SavedStateHandle,
) : BaseViewModel<LoginApprovalState, LoginApprovalEvent, LoginApprovalAction>(
    initialState = savedStateHandle[KEY_STATE]
        ?: LoginApprovalState(
            fingerprint = LoginApprovalArgs(savedStateHandle).fingerprint,
            masterPasswordHash = null,
            publicKey = "",
            requestId = "",
            shouldShowErrorDialog = false,
            viewState = LoginApprovalState.ViewState.Loading,
        ),
) {
    private val dateTimeFormatter
        get() = DateTimeFormatter
            .ofPattern("M/d/yy hh:mm a")
            .withZone(TimeZone.getDefault().toZoneId())

    init {
        viewModelScope.launch {
            trySendAction(
                LoginApprovalAction.Internal.AuthRequestResultReceive(
                    authRequestResult = authRepository.getAuthRequest(state.fingerprint),
                ),
            )
        }
    }

    override fun handleAction(action: LoginApprovalAction) {
        when (action) {
            LoginApprovalAction.ApproveRequestClick -> handleApproveRequestClicked()
            LoginApprovalAction.CloseClick -> handleCloseClicked()
            LoginApprovalAction.DeclineRequestClick -> handleDeclineRequestClicked()
            LoginApprovalAction.ErrorDialogDismiss -> handleErrorDialogDismissed()

            is LoginApprovalAction.Internal.ApproveRequestResultReceive -> {
                handleApproveRequestResultReceived(action)
            }

            is LoginApprovalAction.Internal.AuthRequestResultReceive -> {
                handleAuthRequestResultReceived(action)
            }

            is LoginApprovalAction.Internal.DeclineRequestResultReceive -> {
                handleDeclineRequestResultReceived(action)
            }
        }
    }

    private fun handleApproveRequestClicked() {
        viewModelScope.launch {
            trySendAction(
                LoginApprovalAction.Internal.DeclineRequestResultReceive(
                    result = authRepository.updateAuthRequest(
                        requestId = mutableStateFlow.value.requestId,
                        masterPasswordHash = mutableStateFlow.value.masterPasswordHash,
                        publicKey = mutableStateFlow.value.publicKey,
                        isApproved = true,
                    ),
                ),
            )
        }
    }

    private fun handleCloseClicked() {
        sendEvent(LoginApprovalEvent.NavigateBack)
    }

    private fun handleDeclineRequestClicked() {
        viewModelScope.launch {
            trySendAction(
                LoginApprovalAction.Internal.DeclineRequestResultReceive(
                    result = authRepository.updateAuthRequest(
                        requestId = mutableStateFlow.value.requestId,
                        masterPasswordHash = mutableStateFlow.value.masterPasswordHash,
                        publicKey = mutableStateFlow.value.publicKey,
                        isApproved = false,
                    ),
                ),
            )
        }
    }

    private fun handleErrorDialogDismissed() {
        mutableStateFlow.update {
            it.copy(shouldShowErrorDialog = false)
        }
    }

    private fun handleApproveRequestResultReceived(
        action: LoginApprovalAction.Internal.ApproveRequestResultReceive,
    ) {
        when (action.result) {
            is AuthRequestResult.Success -> {
                sendEvent(LoginApprovalEvent.ShowToast(R.string.login_approved.asText()))
                sendEvent(LoginApprovalEvent.NavigateBack)
            }

            is AuthRequestResult.Error -> {
                mutableStateFlow.update {
                    it.copy(shouldShowErrorDialog = true)
                }
            }
        }
    }

    private fun handleAuthRequestResultReceived(
        action: LoginApprovalAction.Internal.AuthRequestResultReceive,
    ) {
        val email = authRepository.userStateFlow.value?.activeAccount?.email ?: return
        when (val result = action.authRequestResult) {
            is AuthRequestResult.Success -> mutableStateFlow.update {
                it.copy(
                    masterPasswordHash = result.authRequest.masterPasswordHash,
                    publicKey = result.authRequest.publicKey,
                    requestId = result.authRequest.id,
                    viewState = LoginApprovalState.ViewState.Content(
                        deviceType = result.authRequest.platform,
                        domainUrl = result.authRequest.originUrl,
                        email = email,
                        fingerprint = result.authRequest.fingerprint,
                        ipAddress = result.authRequest.ipAddress,
                        time = dateTimeFormatter.format(result.authRequest.creationDate),
                    ),
                )
            }

            is AuthRequestResult.Error -> mutableStateFlow.update {
                it.copy(
                    viewState = LoginApprovalState.ViewState.Error,
                )
            }
        }
    }

    private fun handleDeclineRequestResultReceived(
        action: LoginApprovalAction.Internal.DeclineRequestResultReceive,
    ) {
        when (action.result) {
            is AuthRequestResult.Success -> {
                sendEvent(LoginApprovalEvent.NavigateBack)
            }

            is AuthRequestResult.Error -> {
                mutableStateFlow.update {
                    it.copy(shouldShowErrorDialog = true)
                }
            }
        }
    }
}

/**
 * Models state for the Login Approval screen.
 */
@Parcelize
data class LoginApprovalState(
    val viewState: ViewState,
    val shouldShowErrorDialog: Boolean,
    // Internal
    val fingerprint: String,
    val masterPasswordHash: String?,
    val publicKey: String,
    val requestId: String,
) : Parcelable {
    /**
     * Represents the specific view states for the [LoginApprovalScreen].
     */
    @Parcelize
    sealed class ViewState : Parcelable {
        /**
         * Content state for the [LoginApprovalScreen].
         */
        @Parcelize
        data class Content(
            val deviceType: String,
            val domainUrl: String,
            val email: String,
            val fingerprint: String,
            val ipAddress: String,
            val time: String,
        ) : ViewState()

        /**
         * Represents a state where the [LoginApprovalScreen] is unable to display data due to an
         * error retrieving it.
         */
        @Parcelize
        data object Error : ViewState()

        /**
         * Loading state for the [LoginApprovalScreen], signifying that the content is being
         * processed.
         */
        @Parcelize
        data object Loading : ViewState()
    }
}

/**
 * Models events for the Login Approval screen.
 */
sealed class LoginApprovalEvent {
    /**
     * Navigates back.
     */
    data object NavigateBack : LoginApprovalEvent()

    /**
     * Displays the [message] in a toast.
     */
    data class ShowToast(
        val message: Text,
    ) : LoginApprovalEvent()
}

/**
 * Models actions for the Login Approval screen.
 */
sealed class LoginApprovalAction {
    /**
     * The user has clicked the Confirm login button.
     */
    data object ApproveRequestClick : LoginApprovalAction()

    /**
     * The user has clicked the close button.
     */
    data object CloseClick : LoginApprovalAction()

    /**
     * The user has clicked the Decline login button.
     */
    data object DeclineRequestClick : LoginApprovalAction()

    /**
     * User dismissed the error dialog.
     */
    data object ErrorDialogDismiss : LoginApprovalAction()

    /**
     * Models action the view model could send itself.
     */
    sealed class Internal : LoginApprovalAction() {
        /**
         * A new result for a request to approve this request has been received.
         */
        data class ApproveRequestResultReceive(
            val result: AuthRequestResult,
        ) : Internal()

        /**
         * An auth request result has been received to populate the data on the screen.
         */
        data class AuthRequestResultReceive(
            val authRequestResult: AuthRequestResult,
        ) : Internal()

        /**
         * A new result for a request to decline this request has been received.
         */
        data class DeclineRequestResultReceive(
            val result: AuthRequestResult,
        ) : Internal()
    }
}