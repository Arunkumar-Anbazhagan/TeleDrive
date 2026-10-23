package com.drdisagree.teledrive.data.repository

internal class FieldChoice(
    private val local: FileBase?,
    private val remote: FileBase?,
    private val base: FileBase?,
    private val localIsNewer: Boolean
) {

    fun keepsLocal(field: (FileBase) -> Any?): Boolean {
        val mine = local ?: return false
        val theirs = remote ?: return true
        val agreed = base ?: return localIsNewer
        val changedHere = field(mine) != field(agreed)
        val changedThere = field(theirs) != field(agreed)
        return changedHere && (!changedThere || localIsNewer)
    }

    fun remoteChanged(field: (FileBase) -> Any?): Boolean {
        val theirs = remote ?: return false
        val agreed = base ?: return false
        return field(theirs) != field(agreed)
    }
}
