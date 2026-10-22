package com.shici.app.core.u1.host
import com.shici.app.core.*
import com.shici.app.core.q1.host.*
import java.util.UUID
/** Main UI and callbacks verbatim. AlertDialog/View/queues explicit stand-ins; repository uses real SQLite RPC. */
class DialogWindow {fun setBackgroundDrawable(v:Any){};fun setSoftInputMode(v:Int){};fun setLayout(w:Int,h:Int){}}
class Configuration {val screenWidthDp=1200}
val Resources.configuration:Configuration get()=Configuration()
class ViewGroup {class LayoutParams {companion object {const val WRAP_CONTENT=-2}}}
class AlertDialog(val root:View?) {
 val window:DialogWindow?=DialogWindow()
 fun setView(v:View,a:Int,b:Int,c:Int,d:Int){root!!.addView(v)}
 var isShowing=false;var dismiss:(()->Unit)?=null
 var dismissQueue:Queue?=null;var dismissDeliveries=0
 fun show(){isShowing=true};fun dismiss(){if(!isShowing)return;isShowing=false;root?.detach();if(dismissQueue!=null)dismissQueue!!.post{dismiss?.invoke()}else dismiss?.invoke()}
 fun setOnDismissListener(f:(Any?)->Unit){dismiss={dismissDeliveries++;f(null)}}
 class Builder(val ctx:Context) {var v:View?=null;fun setView(n:View)=apply{v=n};fun setTitle(s:String)=this;fun setMessage(s:String)=this
 fun setNegativeButton(s:String,f:((Any?,Int)->Unit)?)=this;fun setPositiveButton(s:String,f:(Any?,Int)->Unit)=this
 fun create()=AlertDialog(v?:View(ctx));fun show()=create().also{it.show()}}
}
private data class LookupRead(
    val result: LookupResult,
    val user: UserDictionaryEntry?,
    val revision: Long,
    val stable: Boolean
) {
    fun isCurrent(now: Long): Boolean = stable && revision == now
}

typealias ManualHost = MainActivity
class MainActivity:Context(){
 val app=App();val main=Queue();val manualGate=QueryGate();var activeDialog:AlertDialog?=null
 val messages=mutableListOf<String>();fun toast(s:String){messages+=s};fun panel()=column()
 fun query(raw:String){manualLookup(raw)}
 fun nodes()=activeDialog?.root?.all()?:emptyList()
 fun pump(){app.worker.all();main.all()}
 fun click(s:String){nodes().first{it is Button && it.text==s}.click()}
 fun close(){activeDialog?.dismiss()};fun stop(){onStop()};fun destroy(){onDestroy()}
 fun refresh(version:Long){}
    private val dataListener: (Long) -> Unit = { version ->
        // A committed deletion may arrive while a manual "已收录" card is still open; invalidate it so
        // its old undo/correct buttons cannot act on a re-collected record. Other words are untouched.
        val guard = manualGuard
        if (guard != null && !guard.invalid && app.repository.deletions.isStale(guard.key, guard.credential)) {
            main.post { if (manualGuard === guard) invalidateManualCard(guard) }
        }
        // An already-displayed uncollected card belongs to the world its query started in. A restore
        // (or this key's deletion) must retire its write entry too; otherwise an old card could open
        // a new editor after the restore and capture the new world's credential.
        val missing = manualMissingGuard
        if (missing != null && !missing.invalid && app.repository.deletions.isStale(missing.key, missing.credential)) {
            main.post { if (manualMissingGuard === missing) invalidateManualMissing(missing) }
        }
        main.post { refresh(version) }
    }
 init {app.repository.changes.observe(dataListener);app.repository.onRealBound={app.repository.changes.observe(dataListener)}}
 val statusListener=Any();val refreshGate=Noop();val exportSession=LearningExportSession();val backupExportSession=LearningBackupExportSession();val restoreSession=LearningRestoreSession()
 var exportDialog:AlertDialog?=null;var restorePreviewDialog:AlertDialog?=null
 class Noop {fun stop(){};fun onDestroy(){}}
 val studyScreenDelegate=lazy{Noop()};val studyScreen by studyScreenDelegate
 fun updateExportButton(){};fun updateBackupButtons(){}
    private class SupplementEditorOwner { @Volatile var closed = false }
    private class SupplementEditorLease(val owner: SupplementEditorOwner, val admission: StudyAdmission) {
        @Volatile var closed = false
    }
    private val supplementEditorLeases = mutableSetOf<SupplementEditorLease>()
    private fun closeSupplementEditor(lease: SupplementEditorLease) {
        lease.closed = true
        lease.admission.invalidate()
        supplementEditorLeases -= lease
    }
    // Ends every editor, regardless of the dialog that opened it: used by onStop/onDestroy.
    private fun closeSupplementEditors() {
        supplementEditorLeases.toList().forEach { closeSupplementEditor(it) }
    }
    // A dialog's dismiss ends only the editors that dialog opened; another window's live editor is
    // untouched, so its already-running or later save is still admitted on its own owner.
    private fun closeSupplementEditorOwner(owner: SupplementEditorOwner) {
        owner.closed = true
        supplementEditorLeases.toList().forEach { lease ->
            if (lease.owner === owner) closeSupplementEditor(lease)
        }
    }
    // The visible manual "已收录" card, invalidated if its word is deleted while the card is still open.
    private class ManualGuard(
        val key: String, val credential: Long,
        val status: TextView, val undo: Button, val correct: Button, val retry: Button,
        val supplement: Button
    ) { @Volatile var invalid = false }
    @Volatile private var manualGuard: ManualGuard? = null
    // The visible manual "未收录" card. It carries the credential captured when its query started, so
    // neither a late result nor a re-opened editor can silently bind to a newer deletion/restore world.
    private class ManualMissingGuard(
        val key: String, val credential: Long,
        val add: Button?, val collect: Button, val status: TextView,
        val closeEditor: () -> Unit
    ) { @Volatile var invalid = false }
    @Volatile private var manualMissingGuard: ManualMissingGuard? = null
    private fun manualLookup(raw: String, replaces: String? = null, originalRaw: String = raw,
                             credentialOverride: Long? = null) {
        val token = manualGate.next()
        // Captured at query start and carried through display, save and retry; never refreshed later.
        // An explicit retry/collect from a still-current card may pass its own query credential.
        val deletionCredential = credentialOverride ?: app.repository.deletions.capture()
        app.worker.execute {
            if (!manualGate.accepts(token)) return@execute
            // One worker-side resolution for the original asset + local supplements; the current user
            // row is carried separately so the editor never has to parse the merged display text.
            val lookup = runCatching {
                // The committed revision is read immediately before and after the actual lookup. A
                // commit in between means the merged entry and the user row may belong to different
                // snapshots; such a read is rejected rather than mixed or relabelled later.
                val before = app.repository.changes.version
                val result = app.lookupWord(raw)
                val user = (result as? LookupResult.Match)?.let { app.repository.userEntry(it.form) }
                val after = app.repository.changes.version
                LookupRead(result, user, after, before == after)
            }
            main.post {
                if (isDestroyed || !manualGate.accepts(token)) return@post
                lookup.onSuccess { read ->
                    val result = read.result
                    when (result) {
                        LookupResult.Invalid -> toast("请输入一个有效英文词头")
                        is LookupResult.Missing -> {
                            // The result belongs to the world the query started in. A restore/delete
                            // that committed while the worker ran must reject it explicitly instead of
                            // displaying a card whose save would bind to the new world.
                            if (app.repository.deletions.isStale(result.form, deletionCredential)) {
                                toast("查询已失效（词条已删除或数据已恢复），请重新查询")
                            } else if (!read.isCurrent(app.repository.changes.version)) {
                                // A supplement (or any committed data) changed after this read: drawing
                                // the old result would hide the current user meaning, so no card is made.
                                showManualLookupExpired()
                            } else showManualMissing(result.form, raw, replaces, deletionCredential)
                        }
                        is LookupResult.Match -> {
                            val entry = result.entries.single()
                            if (app.repository.deletions.isStale(entry.key, deletionCredential)) {
                                toast("查询已失效（词条已删除或数据已恢复），请重新查询")
                            } else if (!read.isCurrent(app.repository.changes.version)) {
                                showManualLookupExpired()
                            } else showManualDefinition(originalRaw, result.form, entry, read.user, replaces,
                                deletionCredential)
                        }
                    }
                }.onFailure { toast("词库不可用：${it.message}；未生成释义或新增记录") }
            }
        }
    }

    /**
     * A lookup read before a committed change is never drawn as a writable card: its old meaning (and
     * the separately captured user row) could hide a current supplement. This window is read-only and
     * requires a fresh explicit query.
     */
    private fun showManualMissing(form: String, raw: String, replaces: String?, deletionCredential: Long) {
        // Display-entry check: a stale query must not create any writable card at all.
        if (app.repository.deletions.isStale(form, deletionCredential)) {
            toast("查询已失效（词条已删除或数据已恢复），请重新查询")
            return
        }
        val content = panel()
        content.addView(text(form, 28f, bold = true).apply { typeface = Typeface.create("serif", Typeface.BOLD) })
        content.addView(text("原始词库未收录这个词。可以只在本机新增用户词条；保存本身不会产生查询记录或生词。", 16f, Paper.secondary))
        val canAdd = PersonalDictionary.normalizeNewKey(form) == form
        if (!canAdd) {
            content.addView(text("该结果不是单个规范英文词头（可能是短语或多词），不能作为新词头；请修改为一个英文单词。", 15f, Paper.warning, true))
        }
        content.space(8)
        val meaningView = text("", 18f)
        val status = text("", 15f, Paper.secondary)
        val editorHost = column()
        var editorRoot: LinearLayout? = null
        var currentUser: UserDictionaryEntry? = null
        // This dialog owns only the supplement editors opened from it; a later window's editor lease
        // must survive this dialog's late dismiss.
        val editorOwner = SupplementEditorOwner()
        lateinit var dialog: AlertDialog
        lateinit var guard: ManualMissingGuard
        val collect = button("查询并收录", true) {
            // A restore/delete committed after this card appeared retires the card before any new
            // explicit query can be launched from it.
            if (app.repository.deletions.isStale(form, deletionCredential)) {
                invalidateManualMissing(guard); return@button
            }
            dialog.dismiss()
            // This is an explicit, user-initiated active query in the card's still-current world and
            // may enter personal vocabulary. It keeps the original query credential instead of
            // recapturing, so a later restore cannot be laundered into a fresh write identity.
            manualLookup(form, replaces, raw, deletionCredential)
        }.apply { visibility = View.GONE }
        lateinit var add: Button
        if (canAdd) {
            add = button("新增这个词", true) {
                // The editor is bound to this query's original credential, never a fresh capture at
                // display time: after a restore the old card cannot mint a writable new-world editor.
                if (app.repository.deletions.isStale(form, deletionCredential)) {
                    invalidateManualMissing(guard); return@button
                }
                add.isEnabled = false
                editorRoot?.let { editorHost.removeView(it) }
                editorRoot = addSupplementEditor(editorHost, editorOwner, form, form, null, currentUser,
                    deletionCredential, onSaved = { saved, merged ->
                        currentUser = saved
                        meaningView.text = "${merged.partOfSpeech} ${merged.meaning}".trim()
                        status.text = "已保存到本机用户词库（仅本机；尚未收录为生词）"
                        status.setTextColor(Paper.green)
                        collect.visibility = View.VISIBLE
                        add.text = "重新编辑词义"
                        add.isEnabled = true
                    }, onClosed = { add.isEnabled = true }, newHeadword = true)
            }
            content.addView(add); content.space(4)
        }
        content.addView(collect); content.space(4)
        content.addView(meaningView); content.addView(status)
        content.addView(editorHost)
        dialog = AlertDialog.Builder(this)
            .setView(ScrollView(this).apply { addView(content) })
            .setNegativeButton("关闭", null).create()
        guard = ManualMissingGuard(form, deletionCredential, if (canAdd) add else null, collect, status) {
            closeSupplementEditorOwner(editorOwner)
        }
        // Closing the dialog ends only the editors this dialog opened, even before the detached-view callback.
        dialog.setOnDismissListener {
            closeSupplementEditorOwner(editorOwner)
            if (manualMissingGuard === guard) manualMissingGuard = null
        }
        activeDialog?.dismiss(); activeDialog = dialog
        dialog.show()
        manualMissingGuard = guard
    }
    private fun invalidateManualMissing(guard: ManualMissingGuard) {
        guard.invalid = true
        guard.add?.apply { isEnabled = false; text = "查询已失效" }
        guard.collect.isEnabled = false
        guard.status.text = "该词条已删除或数据已恢复，请重新查询"
        guard.status.setTextColor(Paper.error)
        guard.closeEditor()
    }
    private fun showManualDefinition(raw: String, form: String, entry: DictionaryEntry,
                                      initialUser: UserDictionaryEntry?, replaces: String?,
                                      deletionCredential: Long) {
        val content = panel()
        content.addView(text(entry.headword, 28f, bold = true).apply { typeface = Typeface.create("serif", Typeface.BOLD) })
        val meta = text("原词形：$form · ${entry.partOfSpeech}")
        val meaningView = text(entry.meaning, 18f)
        val sourceView = text(PersonalDictionary.label(entry), 14f, Paper.secondary)
        val provenanceView = text(PersonalDictionary.provenance(entry), 14f, Paper.warning)
        content.addView(meta); content.addView(meaningView)
        content.addView(sourceView); content.addView(provenanceView)
        val status = text("保存中…", 16f, Paper.secondary); content.addView(status)
        val undo = button("撤销本次收录") {}; undo.isEnabled = false; content.addView(undo); content.space(8)
        val correct = button("纠错") {}; correct.isEnabled = false; content.addView(correct); content.space(8)
        val retry = button("重试保存") {}; retry.visibility = View.GONE; content.addView(retry)
        var currentUser = initialUser
        val supplementStatus = text("", 14f, Paper.secondary)
        val editorHost = column()
        var editorRoot: LinearLayout? = null
        // This card owns only the supplement editors opened from it, so a late dismiss of an older
        // card cannot close a newer card's live editor.
        val editorOwner = SupplementEditorOwner()
        lateinit var guard: ManualGuard
        val supplement = button("补充/编辑词义") {
            if (manualCardStale(guard)) { invalidateManualCard(guard); return@button }
            val original = if (entry.source == PersonalDictionary.SOURCE) null
            else PersonalDictionary.storedEntry(entry)
            editorRoot?.let { editorHost.removeView(it) }
            editorRoot = addSupplementEditor(editorHost, editorOwner, entry.key, entry.headword, original, currentUser,
                deletionCredential, onSaved = { saved, merged ->
                currentUser = saved
                meta.text = "原词形：$form · ${merged.partOfSpeech}"
                meaningView.text = merged.meaning
                sourceView.text = PersonalDictionary.label(merged)
                provenanceView.text = PersonalDictionary.provenance(merged)
                supplementStatus.text = "用户补充已保存到本机；本次查询记录不受影响"
                supplementStatus.setTextColor(Paper.green)
            })
        }
        content.addView(supplement)
        content.addView(supplementStatus)
        content.addView(editorHost)
        val dialog = AlertDialog.Builder(this).setView(ScrollView(this).apply { addView(content) }).setNegativeButton("关闭", null).create()
        activeDialog?.dismiss(); activeDialog = dialog
        val token = manualGate.next()
        guard = ManualGuard(entry.key, deletionCredential, status, undo, correct, retry, supplement)
        dialog.setOnDismissListener {
            // Closing this card window closes only the editors it opened; a newer card's editor lease
            // stays live, so its own save is still admitted on its owner.
            closeSupplementEditorOwner(editorOwner)
            if (manualGuard === guard) manualGuard = null
            if (manualGate.accepts(token)) manualGate.next()
        }
        dialog.show()
        manualGuard = guard
        val event = QueryEvent(UUID.randomUUID().toString(), entry.key, raw, form, System.currentTimeMillis())
        fun save() {
            // A stale card (word deleted after this query started) must not start/retry a save.
            if (manualCardStale(guard)) { invalidateManualCard(guard); return }
            retry.visibility = View.GONE; status.text = "保存中…"
            app.worker.execute {
                if (!manualGate.accepts(token)) return@execute
                val result = runCatching { app.repository.record(event, entry, replaces, deletionCredential) { manualGate.accepts(token) } }
                main.post {
                    if (!isDestroyed && manualGate.accepts(token) && dialog.isShowing && manualGuard === guard && !guard.invalid) {
                        val error = result.exceptionOrNull()
                        val saved = result.getOrNull() == true
                        when {
                            // A committed deletion (even before the invalidation message runs) wins over a
                            // late error/success: never re-show retry nor overwrite the invalid text.
                            manualCardStale(guard) -> invalidateManualCard(guard)
                            error != null -> {
                                status.text = "保存失败：${error.message}"; status.setTextColor(Paper.error)
                                undo.isEnabled = false; correct.isEnabled = false; retry.visibility = View.VISIBLE
                            }
                            saved -> {
                                status.text = "已收录"; status.setTextColor(Paper.green)
                                undo.isEnabled = true; correct.isEnabled = true; retry.visibility = View.GONE
                            }
                            else -> invalidateManualCard(guard)
                        }
                    }
                }
            }
        }
        retry.setOnClickListener { save() }
        undo.setOnClickListener {
            // Reject an expired card at the entry too, not only after the worker returns.
            if (manualCardStale(guard)) { invalidateManualCard(guard); return@setOnClickListener }
            undo.isEnabled = false; correct.isEnabled = false
            app.worker.execute {
                // A no-op revoke (word already deleted / event gone) must not be reported as success.
                val result = runCatching { app.repository.revokeEvent(event.id, entry.key, deletionCredential) }
                main.post {
                    if (!isDestroyed && manualGate.accepts(token) && dialog.isShowing && manualGuard === guard) {
                        // Deletion wins over a late revoke result: never re-enable undo/correct nor report REVOKED.
                        if (manualCardStale(guard)) invalidateManualCard(guard) else {
                            val outcome = result.getOrNull()
                            when {
                                result.isFailure -> {
                                    status.text = "撤销失败：${result.exceptionOrNull()?.message}"
                                    undo.isEnabled = true; correct.isEnabled = true
                                }
                                outcome == RevokeOutcome.REVOKED -> { status.text = "本次已撤销，历史查询保留"; undo.text = "已撤销" }
                                else -> {
                                    status.text = "撤销失败：该记录已不存在"; status.setTextColor(Paper.error)
                                    undo.isEnabled = false; correct.isEnabled = false
                                }
                            }
                        }
                    }
                }
            }
        }
        correct.setOnClickListener {
            if (manualCardStale(guard)) { invalidateManualCard(guard); return@setOnClickListener }
            dialog.dismiss()
            val input = EditText(this).apply { setText(form); setSingleLine(true) }
            AlertDialog.Builder(this).setTitle("纠正词形").setView(input).setMessage("确认成功后撤销原事件，不重复累计。")
                .setPositiveButton("确认查询") { _, _ -> manualLookup(input.text.toString(), event.id, raw) }.setNegativeButton("取消", null).show()
        }
        save()
    }

    /**
     * Local supplement editor shared by the manual and floating entry points. It edits only the
     * user-owned POS/meaning: the original asset is shown read-only and never rewritten. Saving does
     * not record a query, advance study or change mastery/notes/review state. A stale dialog (restore,
     * deletion, or a newer supplement saved meanwhile) is rejected instead of overwriting newer data.
     */
    private fun addSupplementEditor(host: LinearLayout, owner: SupplementEditorOwner,
                                    entryKey: String, headword: String,
                                    original: DictionaryEntry?, currentUser: UserDictionaryEntry?,
                                    credential: Long,
                                    onSaved: (UserDictionaryEntry, DictionaryEntry) -> Unit,
                                    onClosed: () -> Unit = {},
                                    newHeadword: Boolean = false): LinearLayout {
        // A window that has already been dismissed can never open a new writable editor, and neither
        // can a card whose query world (deletion/restore) has already been replaced. Return a
        // non-editable notice without registering a lease, so no late save can be admitted on it.
        val staleWorld = app.repository.deletions.isStale(entryKey, credential)
        if (owner.closed || staleWorld) {
            val closed = column().apply {
                space(8); divider()
                addView(text(if (owner.closed) "该窗口已关闭，请重新打开后再编辑"
                else "该词条已删除或数据已恢复，请重新查询后再编辑", 15f, Paper.warning, true))
            }
            host.addView(closed)
            onClosed()
            return closed
        }
        val content = column().apply {
            space(8); divider()
            addView(text("补充/编辑词义 · 仅本机", 19f, bold = true))
            addView(text("只编辑用户词性/释义；原词库只读，不会被覆盖，也不计入查询或学习。", 13f, Paper.secondary))
        }
        content.addView(text(headword, 18f, bold = true))
        original?.let {
            content.addView(text("原词库释义（只读）", 15f, bold = true))
            content.addView(text("${it.partOfSpeech} ${it.meaning}".trim(), 15f))
            content.addView(text(PersonalDictionary.provenance(it), 12f, Paper.warning))
        }
        val posInput = inputField("词性（可留空），如 n. / v.", singleLine = true).apply {
            setText(currentUser?.partOfSpeech ?: "")
        }
        val meaningInput = inputField("用户补充释义（必填）", singleLine = false).apply {
            minLines = 3; gravity = Gravity.TOP
            setText(currentUser?.meaning ?: "")
        }
        content.addView(posInput); content.space(4); content.addView(meaningInput)
        val status = text("", 14f, Paper.secondary); content.addView(status)
        val saveButton = button("保存到本机", true) {}
        val cancelButton = button("取消") {}
        content.addView(saveButton); content.space(4); content.addView(cancelButton)
        host.addView(content)
        var alive = true
        var saving = false
        // Bound to the deletion/restore world the originating query started in, passed in explicitly.
        // Opening the editor never captures a fresh credential, so displaying an old card cannot
        // rebind it to the world created by a later restore.
        val editorLease = SupplementEditorLease(owner, StudyAdmission {
            !app.repository.deletions.isStale(entryKey, credential)
        })
        supplementEditorLeases += editorLease
        fun closeEditor() = closeSupplementEditor(editorLease)
        content.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            // Detach ends this editor for good. Reattaching the same old view cannot revive it.
            override fun onViewAttachedToWindow(v: View) { alive = true }
            override fun onViewDetachedFromWindow(v: View) { alive = false; closeEditor() }
        })
        val expectedUpdatedAt = currentUser?.updatedAt ?: 0L
        saveButton.setOnClickListener {
            if (!alive || owner.closed || editorLease.closed || saving) return@setOnClickListener
            val meaning = meaningInput.text.toString()
            if (meaning.isBlank()) {
                status.text = "释义不能为空，未保存"; status.setTextColor(Paper.error)
                return@setOnClickListener
            }
            if (app.repository.deletions.isStale(entryKey, credential)) {
                status.text = "词条已删除或数据已恢复，请重新查询后再编辑"; status.setTextColor(Paper.error)
                return@setOnClickListener
            }
            saving = true
            saveButton.isEnabled = false; cancelButton.isEnabled = false
            status.text = "保存中…"; status.setTextColor(Paper.secondary)
            app.worker.execute {
                val result = app.repository.saveUserEntry(entryKey, posInput.text.toString(), meaning,
                    expectedUpdatedAt = expectedUpdatedAt, newHeadword = newHeadword,
                    admission = editorLease.admission)
                main.post {
                    // A closed/stopped owner suppresses its old UI. It never reports an already
                    // committed save as "not saved"; the admission result below is the truth.
                    if (!alive || owner.closed || editorLease.closed) return@post
                    saving = false; cancelButton.isEnabled = true
                    if (!result.saved) {
                        saveButton.isEnabled = true
                        status.text = result.message; status.setTextColor(Paper.error)
                        return@post
                    }
                    if (app.repository.deletions.isStale(entryKey, credential)) {
                        // The save was admitted and committed before this deletion/restore. Do not
                        // claim it was unwritten, and do not repaint the card from the old world.
                        saveButton.isEnabled = false
                        status.text = "保存已提交，但该词随后被删除或数据已恢复"
                        status.setTextColor(Paper.warning)
                        return@post
                    }
                    val saved = result.entry ?: return@post
                    val merged = PersonalDictionary.merge(original, saved) ?: PersonalDictionary.userOnly(saved)
                    onSaved(saved, merged)
                    status.text = if (result.changed) "已保存到本机用户词库" else "词义未变化，未重复保存"
                    status.setTextColor(Paper.green)
                    // A successful save replaces the old generation; reopen to edit the new text.
                    saveButton.isEnabled = false
                }
            }
        }
        cancelButton.setOnClickListener {
            alive = false
            closeEditor()
            host.removeView(content)
            if (!saving) onClosed()
        }
        return content
    }
    // A manual card is out of date once it is invalidated or its word was deleted after the query
    // credential was captured; both must win over any late success/failure result.
    private fun manualCardStale(guard: ManualGuard): Boolean =
        guard.invalid || app.repository.deletions.isStale(guard.key, guard.credential)
    private fun invalidateManualCard(guard: ManualGuard) {
        guard.invalid = true
        guard.status.text = "未收录：该词已删除或查询已失效"; guard.status.setTextColor(Paper.error)
        guard.undo.isEnabled = false; guard.correct.isEnabled = false; guard.retry.visibility = View.GONE
        guard.supplement.isEnabled = false
    }
    // Retires an open uncollected card without any write path: both the new-headword editor and the
    // explicit collect action stay unavailable once the query's world has been replaced.
    override fun onStop() {
        manualGate.next()
        // The supplement editor belongs to this stopped page: refuse every queued save that has not
        // reached its transaction yet. A save admitted before this point keeps its atomic commit.
        closeSupplementEditors()
        app.repository.changes.remove(dataListener)
        refreshGate.stop()
        if (exportSession.cancelPreparation()) { exportDialog?.dismiss(); updateExportButton() }
        // A backup/restore picker (AWAIT_IMPORT/AWAIT_SAFETY) stops this activity itself, so only the
        // non-picker preparations are cancelled here; a live picker and an in-flight apply are kept.
        if (backupExportSession.cancelPreparation()) updateBackupButtons()
        if (restoreSession.cancelPreparation()) {
            restorePreviewDialog?.dismiss(); restorePreviewDialog = null; updateBackupButtons()
        }
        CaptureStatus.remove(statusListener)
        super.onStop()
    }
    override fun onDestroy() {
        refreshGate.stop(); exportSession.clear(); manualGate.next()
        backupExportSession.clear(); restoreSession.clear()
        // Close an already-queued supplement save even if this page never received onStop.
        closeSupplementEditors()
        // Invalidate the study surface's cross-thread admission before the Activity is gone. Workers
        // that already reached their transaction may finish atomically; ones still reading refuse it.
        if (studyScreenDelegate.isInitialized()) studyScreen.onDestroy()
        restorePreviewDialog?.dismiss(); restorePreviewDialog = null
        activeDialog?.dismiss(); activeDialog = null
        super.onDestroy()
    }
    private fun showManualLookupExpired() {
        val content = panel()
        content.addView(text("查询已失效", 20f, bold = true))
        content.addView(text("查询期间词条或本机补充已更新。为避免显示或保存过期释义，本次旧结果已丢弃；请重新查询。", 16f, Paper.warning))
        content.space(8)
        lateinit var dialog: AlertDialog
        content.addView(button("关闭") { dialog.dismiss() })
        dialog = paperDialog(content)
        showPaperDialog(dialog, 640)
    }

    /**
     * The uncollected state of a manual lookup. Saving a new user headword here is not a query: it
     * creates no QueryEvent, count, vocabulary row or study card. Only the explicit "查询并收录"
     * action after a successful save enters the normal matched-card flow.
     */
    private fun paperDialog(content: LinearLayout): AlertDialog = AlertDialog.Builder(this).create().apply {
        setView(ScrollView(this@MainActivity).apply { addView(content) }, 0, 0, 0, 0)
    }
    private fun showPaperDialog(dialog: AlertDialog, maxWidth: Int, onDismiss: () -> Unit = {}) {
        activeDialog?.dismiss(); activeDialog = dialog; dialog.show()
        dialog.window?.apply {
            setBackgroundDrawable(shape(Paper.surface, border = true))
            setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
            setLayout(dp(minOf(resources.configuration.screenWidthDp - 32, maxWidth)), ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        dialog.setOnDismissListener { if (activeDialog === dialog) activeDialog = null; onDismiss() }
    }
}
