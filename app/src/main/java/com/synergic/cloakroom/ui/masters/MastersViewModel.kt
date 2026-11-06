package com.synergic.cloakroom.ui.masters

import android.net.Uri
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.synergic.cloakroom.data.print.LogoProcessor
import com.synergic.cloakroom.data.repository.RemoteMasterRepositoryFactory
import com.synergic.cloakroom.domain.auth.SessionProvider
import com.synergic.cloakroom.domain.repository.CustomerProfile
import com.synergic.cloakroom.domain.repository.HeaderFooterItem
import com.synergic.cloakroom.domain.repository.IdProofItem
import com.synergic.cloakroom.domain.repository.MasterRepository
import com.synergic.cloakroom.domain.repository.ShiftItem
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Admin → Masters (the admin's own customer) or Super Admin → customer → Masters (route argument
 * `id`): the same screen, with that customer's data on the server. Without a server login (tests,
 * demo data) it works on this phone's database.
 */
@HiltViewModel
class MastersViewModel @Inject constructor(
    localRepository: MasterRepository,
    remoteFactory: RemoteMasterRepositoryFactory,
    savedState: SavedStateHandle,
    sessionProvider: SessionProvider,
    private val logoProcessor: LogoProcessor,
) : ViewModel() {

    private val remote = sessionProvider.current
        ?.let { s ->
            when {
                s.isSuperAdmin -> savedState.get<Long>("id")
                s.isAdmin -> s.customerId
                else -> null
            }
        }
        ?.let { remoteFactory.create(it) }

    private val repository: MasterRepository = remote ?: localRepository

    /** Super admin: ID proof types are shared by every cloak room. */
    val idProofsShared: Boolean = remote?.ownCustomer == false

    /** Station admin on the server: cloak room details (except GSTIN and UPI ID) and ID proof types are set by Synergic. */
    val setBySynergic: Boolean = remote?.ownCustomer == true

    val customer: StateFlow<CustomerProfile?> = repository.observeCustomer()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val idProofs: StateFlow<List<IdProofItem>?> = repository.observeIdProofs()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val lines: StateFlow<List<HeaderFooterItem>?> = repository.observeHeaderFooter()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)
    val shifts: StateFlow<List<ShiftItem>?> = repository.observeShifts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _message = MutableStateFlow<String?>(null)
    /** Result or error of the last action, shown in a snackbar. */
    val message: StateFlow<String?> = _message.asStateFlow()

    init {
        remote?.let { r ->
            viewModelScope.launch {
                runCatching { r.refresh() }.onFailure { _message.value = it.message ?: "Could not load the masters." }
            }
        }
    }

    fun clearMessage() {
        _message.value = null
    }

    /** Runs [action]; [onResult] gets true on success so the dialog can close, false to keep it open. */
    private fun run(done: String?, onResult: (Boolean) -> Unit = {}, action: suspend () -> Unit) {
        viewModelScope.launch {
            runCatching { action() }
                .onSuccess { _message.value = done; onResult(true) }
                .onFailure { _message.value = it.message ?: "Something went wrong."; onResult(false) }
        }
    }

    fun saveCustomer(name: String, address: String, pinCode: String, gstNo: String, upiId: String, onResult: (Boolean) -> Unit) =
        run("Cloak room details saved.", onResult) { repository.updateCustomer(name, address, pinCode, gstNo, upiId) }

    fun saveIdProof(id: Long?, name: String, onResult: (Boolean) -> Unit) =
        run("ID proof saved.", onResult) { repository.saveIdProof(id, name) }

    fun deleteIdProof(item: IdProofItem) = run("\"${item.name}\" removed.") { repository.deleteIdProof(item.id) }

    fun saveLine(item: HeaderFooterItem, onResult: (Boolean) -> Unit) =
        run("Line saved.", onResult) { repository.saveHeaderFooter(item) }

    fun deleteLine(item: HeaderFooterItem) = run("Line removed.") { repository.deleteHeaderFooter(item.id) }

    fun moveLine(item: HeaderFooterItem, up: Boolean) = run(null) { repository.moveHeaderFooter(item.id, up) }

    fun saveShift(id: Long?, name: String, from: String, to: String, onResult: (Boolean) -> Unit) =
        run("Shift saved.", onResult) { repository.saveShift(id, name, from, to) }

    fun deleteShift(item: ShiftItem) = run("Shift \"${item.name}\" removed.") { repository.deleteShift(item.id) }

    /** Converts a chosen picture into a printable black-and-white logo. */
    fun loadLogo(uri: Uri, onLoaded: (ByteArray) -> Unit) {
        viewModelScope.launch {
            runCatching { logoProcessor.fromUri(uri) }
                .onSuccess(onLoaded)
                .onFailure { _message.value = it.message ?: "This picture cannot be used." }
        }
    }
}
