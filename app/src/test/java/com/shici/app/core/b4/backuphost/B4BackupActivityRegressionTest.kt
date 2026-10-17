package com.shici.app.core.b4.backuphost
import com.shici.app.core.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.time.LocalDate
/** Exact production methods; explicit fake queues/views/ContentResolver/repository. NOT Android or SQLite. */
data class Uri(val value:String){override fun toString()=value}
class Intent(val action:String="") {
 var data:Uri?=null
 fun addCategory(s:String)=this;fun setType(s:String)=this;fun putExtra(k:String,v:String)=this
 companion object {const val ACTION_CREATE_DOCUMENT="create";const val ACTION_OPEN_DOCUMENT="open";const val CATEGORY_OPENABLE="openable";const val EXTRA_TITLE="title"}
}
object BuildConfig{const val VERSION_NAME="1.0.0";const val VERSION_CODE=12}
object Paper{val secondary=0;val background=0;val warning=0;val ink=0}
class Queue{val tasks=java.util.ArrayDeque<()->Unit>();fun execute(f:()->Unit){tasks.add(f)};fun post(f:()->Unit){tasks.add(f)};fun one(){tasks.removeFirst().invoke()};fun all(){while(tasks.isNotEmpty())one()}}
open class Node {var isEnabled=true;var text="";var background:Any?=null;var action:(()->Unit)?=null;fun click(){action!!.invoke()};fun space(n:Int){};fun addView(n:Node){};fun setTextColor(n:Int){};fun setPadding(a:Int,b:Int,c:Int,d:Int){}}
class Dialog{var dismissed=false;var callback:(()->Unit)?=null;fun dismiss(){if(!dismissed){dismissed=true;callback?.invoke()}}}
class Config{val screenWidthDp=800};class Resources{val configuration=Config()}
class Gate{fun next(){};fun stop(){}}
object CaptureStatus{fun remove(x:Any){}}
data class LearningBackupSnapshot(val data:LearningBackupData,val revision:Long)
enum class RestoreOutcome{RESTORED,REVISION_CHANGED}
class Repo {
 val changes=LearningChanges();var rows=LearningBackupData(listOf(BackupWord("current","current","n","meaning","old","v",true,"private synthetic")),emptyList())
 var snapshots=0;var restores=0;var failSnapshot=false;var failRestore=false;var expected:Long?=null;var atRestore:(()->Unit)?=null;var atSnapshot:(()->Unit)?=null
 fun backupSnapshot():LearningBackupSnapshot{atSnapshot?.invoke();snapshots++;if(failSnapshot)error("snapshot-failure");return LearningBackupSnapshot(rows,changes.version)}
 fun restoreBackup(d:LearningBackupData,r:Long):RestoreOutcome{atRestore?.invoke();expected=r;if(changes.version!=r)return RestoreOutcome.REVISION_CHANGED;if(failRestore)error("transaction-failure");restores++;rows=d;changes.publishCommitted();return RestoreOutcome.RESTORED}
}
class App{val repository=Repo();val worker=Queue()}
class Resolver {
 val files=mutableMapOf<String,ByteArray>();val reads=mutableListOf<String>();val writes=mutableListOf<String>();var nullInput=false;var nullOutput=false
 var writeFailure=false;var closeFailure=false;var flushFailure=false;var readFailure=false;var mismatch=false;var closed=false;var afterClose:(()->Unit)?=null;var streamFactory:(()->InputStream)?=null
 fun openInputStream(u:Uri):InputStream? {reads+=u.value;if(nullInput)return null;if(readFailure)throw IOException("read-failure");return streamFactory?.invoke()?:ByteArrayInputStream(files.getValue(u.value))}
 fun openOutputStream(u:Uri,mode:String):OutputStream? {writes+=u.value;if(nullOutput)return null;return object:ByteArrayOutputStream(){
 override fun write(b:ByteArray,off:Int,len:Int){if(writeFailure)throw IOException("write-failure");super.write(b,off,len)}
 override fun flush(){if(flushFailure)throw IOException("flush-failure")}
 override fun close(){closed=true;if(closeFailure)throw IOException("close-failure");files[u.value]=if(mismatch)byteArrayOf(0) else toByteArray();afterClose?.invoke()}
 }}
}
open class Base {var isDestroyed=false;open fun onStop(){};open fun onDestroy(){isDestroyed=true}}
class B4BackupActivityRegressionTest:Base(){
 val app=App();val main=Queue();val contentResolver=Resolver();val backupExportSession=LearningBackupExportSession();val restoreSession=LearningRestoreSession();val exportSession=LearningExportSession()
 var backupExportButton:Node?=Node();var restoreButton:Node?=Node();var backupStatusView:Node?=Node();var restorePreviewDialog:Dialog?=null;var activeDialog:Dialog?=null;var exportDialog:Dialog?=null
 val manualGate=Gate();val refreshGate=Gate();val dataListener:(Long)->Unit={};val statusListener=Any();var lostExportRequestCode=-1;var cleaningLostExport=false
 val messages=mutableListOf<String>();val texts=mutableListOf<String>();val buttons=mutableMapOf<String,Node>();val launched=mutableListOf<Pair<String,Int>>();var pickerFailure=false
 val resources=Resources();val RESULT_OK=-1
 fun toast(s:String){messages+=s};fun date(t:Long)="date:$t";fun updateExportButton(){}
 fun panel()=Node();fun column()=Node();fun paperDialog(n:Node)=Dialog();fun text(s:String,size:Float,color:Int=0,bold:Boolean=false)=Node().also{texts+=s}
 fun text(s:String,size:Int,color:Int=0,bold:Boolean=false)=text(s,size.toFloat(),color,bold)
 fun dp(n:Int)=n;fun shape(a:Int,b:Int)=Any();fun inlineAt(n:Int)=false;fun pair(a:Node,b:Node,c:Boolean,d:Int)=Node()
 fun button(s:String,primary:Boolean=false,action:()->Unit)=Node().also{it.action=action;buttons[s]=it}
 fun showPaperDialog(d:Dialog,n:Int,onDismiss:()->Unit){d.callback=onDismiss}
 fun startActivityForResult(i:Intent,id:Int){if(pickerFailure)error("picker-failure");launched+=i.action to id}
 private fun payload(empty:Boolean=false)=LearningBackupData(if(empty)emptyList()else listOf(BackupWord("imported","imported","","legacy","source","old",false,"note")),emptyList())
 private fun frozen(empty:Boolean=false):LearningBackup=LearningBackupCodec.decode(LearningBackupCodec.encode(payload(empty),"legacy",1,123))
 private fun result(uri:String)=Intent().also{it.data=Uri(uri)}
 private fun readySafety(empty:Boolean=false):Int {val id=restoreSession.beginImport()!!;restoreSession.beginReading(id,"input");restoreSession.freezeImport(id,frozen(empty));return restoreSession.confirm()!!}
 private fun settle(){app.worker.all();main.all()}
    private fun updateBackupButtons() {
        val busy = backupExportSession.busy || restoreSession.busy
        backupExportButton?.isEnabled = !busy
        restoreButton?.isEnabled = !busy
        backupStatusView?.text = when {
            restoreSession.busy -> "正在处理恢复：读取并校验备份、准备安全备份和原子覆盖，请勿离开本页"
            backupExportSession.busy -> "正在准备备份文件，请稍候"
            else -> "仅本地备份，不联网、不上传。"
        }
    }
    private fun exportLearningBackup() {
        if (backupExportSession.busy || restoreSession.busy) return
        if (lostExportRequestCode >= 0 || cleaningLostExport) { toast("请先完成或取消当前 PDF 导出"); return }
        val id = backupExportSession.begin() ?: return
        updateBackupButtons()
        app.worker.execute {
            val result = runCatching {
                val snapshot = app.repository.backupSnapshot()
                LearningBackupCodec.encode(snapshot.data, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, System.currentTimeMillis())
            }
            main.post {
                if (isDestroyed) return@post
                result.onSuccess { bytes ->
                    if (!backupExportSession.freeze(id, bytes)) return@onSuccess
                    updateBackupButtons()
                    val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                        .setType(LearningBackupCodec.MIME_TYPE)
                        .putExtra(Intent.EXTRA_TITLE, "拾词-学习数据备份-${LocalDate.now()}${LearningBackupCodec.FILE_SUFFIX}")
                    runCatching { startActivityForResult(intent, id) }.onFailure {
                        backupExportSession.cancel(id); updateBackupButtons()
                        toast("无法打开文件保存：${it.message}")
                    }
                }.onFailure {
                    backupExportSession.cancel(id); updateBackupButtons()
                    toast("准备备份失败：${it.message}")
                }
            }
        }
    }
    private fun handleBackupExportResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (backupExportSession.fileRequestCode != requestCode) return
        if (resultCode != RESULT_OK || data?.data == null) {
            backupExportSession.cancel(requestCode); updateBackupButtons(); toast("已取消备份"); return
        }
        val bytes = backupExportSession.takeResult(requestCode) ?: return
        val uri = data.data!!
        app.worker.execute {
            val error = runCatching {
                val output = contentResolver.openOutputStream(uri, "wt") ?: error("无法写入所选位置")
                output.use { it.write(bytes); it.flush() }
            }.exceptionOrNull()
            main.post {
                if (isDestroyed) return@post
                backupExportSession.cancel(requestCode)
                updateBackupButtons()
                toast(if (error == null) "学习数据备份已写入所选位置" else "备份失败：${error.message}；目标文件可能不完整，请删除后重试")
            }
        }
    }
    private fun startRestore() {
        if (backupExportSession.busy || restoreSession.busy) return
        if (lostExportRequestCode >= 0 || cleaningLostExport) { toast("请先完成或取消当前 PDF 导出"); return }
        val id = restoreSession.beginImport() ?: return
        updateBackupButtons()
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
        runCatching { startActivityForResult(intent, id) }.onFailure {
            restoreSession.cancelImport(id); updateBackupButtons()
            toast("无法打开文件选择：${it.message}")
        }
    }
    private fun handleRestoreImportResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (restoreSession.importRequestCode != requestCode) return
        if (resultCode != RESULT_OK || data?.data == null) {
            restoreSession.cancelImport(requestCode); updateBackupButtons(); toast("已取消恢复"); return
        }
        val uri = data.data!!
        if (!restoreSession.beginReading(requestCode, uri.toString())) return
        updateBackupButtons()
        app.worker.execute {
            // Read and validate the whole file first; the frozen payload is never re-read at apply time.
            val result = runCatching { LearningBackupCodec.decode(readBoundedBytes(uri)) }
            main.post {
                if (isDestroyed) return@post
                result.onSuccess { backup ->
                    if (!restoreSession.freezeImport(requestCode, backup)) return@onSuccess
                    updateBackupButtons()
                    showRestorePreview(backup)
                }.onFailure {
                    restoreSession.cancelImport(requestCode); updateBackupButtons()
                    toast("无法读取备份：${it.message}")
                }
            }
        }
    }
    private fun showRestorePreview(backup: LearningBackup) {
        val content = panel()
        val dialog = paperDialog(content)
        content.addView(text("从备份完整恢复", 24f, bold = true)); content.space(12)
        content.addView(text("备份创建时间：${date(backup.createdAt)}", 18f))
        content.addView(text("词条 ${backup.wordCount} 个 · 事件 ${backup.eventCount} 条", 18f))
        if (backup.legacyNoStudyData) {
            content.addView(text("这是旧版格式（v1）备份：只含生词与查询记录，不含背词进度。", 16f, Paper.warning, true))
            content.addView(text("恢复后背词进度为空，所选词书与每日目标恢复为默认值。", 16f, Paper.warning))
        } else {
            content.addView(text("背词进度：已学 ${backup.stateCount} 词 · 反馈 ${backup.reviewCount} 条${if (backup.data.session != null) " · 含 1 张未答卡" else ""}", 18f))
        }
        content.addView(text("备份应用版本：${backup.appVersionName}（${backup.appVersionCode}）", 16f, Paper.secondary)); content.space(12)
        val warning = column().apply {
            setPadding(dp(16), dp(12), dp(16), dp(12)); background = shape(Paper.background, 8)
            addView(text(if (backup.wordCount == 0 && backup.eventCount == 0)
                "这是一个空备份：确认恢复后会清空当前全部学习数据。" else
                "恢复会用备份内容完整替换当前全部学习数据（不合并、不猜测冲突）；备份里没有的词也会被移除。",
                16f, Paper.warning, true))
            addView(text("恢复前必须先把当前数据另存为一个安全备份文件；只有写入关闭并读回校验一致、且数据未在此期间变化时才会覆盖。", 16f, Paper.secondary))
            addView(text("备份是本地明文个人数据，未加密、不防恶意篡改。", 16f, Paper.secondary))
        }
        content.addView(warning); content.space(24)
        val cancel = button("取消") { dialog.dismiss() }.apply { background = shape(Paper.background, 8); setTextColor(Paper.ink) }
        val confirm = button("继续并选择安全备份位置", true) {
            val safetyId = restoreSession.confirm() ?: return@button
            dialog.dismiss()
            updateBackupButtons()
            val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE)
                .setType(LearningBackupCodec.MIME_TYPE)
                .putExtra(Intent.EXTRA_TITLE, "拾词-恢复前安全备份-${LocalDate.now()}${LearningBackupCodec.FILE_SUFFIX}")
            runCatching { startActivityForResult(intent, safetyId) }.onFailure {
                restoreSession.cancelSafety(safetyId); updateBackupButtons()
                toast("无法打开安全备份保存位置：${it.message}")
            }
        }
        content.addView(pair(cancel, confirm, inlineAt(minOf(resources.configuration.screenWidthDp - 32, 560) - 48), 12))
        showPaperDialog(dialog, 560) {
            if (restorePreviewDialog === dialog) restorePreviewDialog = null
            if (restoreSession.cancelPreview()) updateBackupButtons()
        }
        restorePreviewDialog = dialog
    }
    private fun handleRestoreSafetyResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (restoreSession.safetyRequestCode != requestCode) return
        if (resultCode != RESULT_OK || data?.data == null) {
            restoreSession.cancelSafety(requestCode); updateBackupButtons(); toast("已取消恢复：未选择安全备份位置"); return
        }
        val safetyUri = data.data!!
        val importUri = restoreSession.pendingImportUri
        if (importUri != null && safetyUri.toString() == importUri) {
            restoreSession.cancelSafety(requestCode); updateBackupButtons()
            toast("安全备份位置不能与导入的备份文件相同，已取消恢复"); return
        }
        val backup = restoreSession.beginApply(requestCode) ?: return
        // Cross-thread cancellation identity for exactly this apply. onDestroy (through restoreSession.clear),
        // a replaced session or an invalidated apply must not let this queued worker overwrite the database.
        val apply = restoreSession.applyHandle ?: return
        updateBackupButtons()
        app.worker.execute {
            // Revalidate on the worker thread before any I/O: an apply invalidated while this task was
            // queued (destroyed/replaced session) must not even write the safety backup.
            if (!apply.isActive()) return@execute
            val result = runCatching {
                val pre = app.repository.backupSnapshot()
                val encoded = LearningBackupCodec.encode(pre.data, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE, System.currentTimeMillis())
                if (!writeSafetyBackup(safetyUri, encoded)) error("恢复前安全备份未能读回校验一致")
                // Same worker and pre-transaction check: a change since the snapshot must not be overwritten.
                // This is also the last admission gate: admit() is linearized against session invalidation, so
                // a destroy/clear before it must not write, while an already admitted transaction is allowed to
                // commit atomically — no half-write, no false cancellation claim, no forced mid-commit rollback.
                if (!apply.admit()) return@runCatching null
                app.repository.restoreBackup(backup.data, pre.revision)
            }
            main.post {
                if (isDestroyed) return@post
                // Settle only this operation; finish() is request/phase guarded, so a replaced session
                // keeps its own busy state and a late callback cannot end the new session.
                restoreSession.finish(requestCode)
                updateBackupButtons()
                result.onSuccess { outcome ->
                    when (outcome) {
                        RestoreOutcome.RESTORED -> {
                            restorePreviewDialog?.dismiss(); restorePreviewDialog = null
                            toast("已完整恢复：替换为备份中的 ${backup.wordCount} 个词、${backup.eventCount} 条事件")
                        }
                        RestoreOutcome.REVISION_CHANGED ->
                            toast("学习数据在准备期间有新变化，本次未覆盖；请重新开始恢复")
                        else -> Unit
                    }
                }.onFailure { toast("恢复失败，学习数据未改变：${it.message}") }
            }
        }
    }
    private fun readBoundedBytes(uri: Uri): ByteArray {
        val input = contentResolver.openInputStream(uri) ?: throw BackupFormatException("无法读取所选文件")
        return input.use { stream ->
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(64 * 1024)
            var total = 0L
            while (true) {
                val read = stream.read(buffer)
                if (read < 0) break
                total += read
                if (total > LearningBackupCodec.MAX_FILE_BYTES) throw BackupFormatException("备份文件超过 ${LearningBackupCodec.MAX_BYTES_LABEL} 上限")
                out.write(buffer, 0, read)
            }
            out.toByteArray()
        }
    }
    private fun writeSafetyBackup(uri: Uri, bytes: ByteArray): Boolean {
        val output = contentResolver.openOutputStream(uri, "wt") ?: throw java.io.IOException("无法写入所选安全备份位置")
        output.use { it.write(bytes); it.flush() }
        return readBoundedBytes(uri).contentEquals(bytes)
    }
    override fun onStop() {
        manualGate.next()
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
    private class UnopenedStudySurface { fun onDestroy() {} }
    private val studyScreenDelegate=lazy { UnopenedStudySurface() }
    private val studyScreen by studyScreenDelegate
    override fun onDestroy() {
        refreshGate.stop(); exportSession.clear(); manualGate.next()
        backupExportSession.clear(); restoreSession.clear()
        // Invalidate the study surface's cross-thread admission before the Activity is gone. Workers
        // that already reached their transaction may finish atomically; ones still reading refuse it.
        if (studyScreenDelegate.isInitialized()) studyScreen.onDestroy()
        restorePreviewDialog?.dismiss(); restorePreviewDialog = null
        activeDialog?.dismiss(); activeDialog = null
        super.onDestroy()
    }
 fun frozenImportReadOncePreviewConfirmAndSafetyBeforeApply(){
  val original=app.repository.rows;contentResolver.files["input"]=LearningBackupCodec.encode(payload(),"legacy",1,123)
  startRestore();val id=restoreSession.importRequestCode!!;onStop();handleRestoreImportResult(id,RESULT_OK,result("input"));settle()
  assertEquals(0,app.repository.restores);assertTrue(texts.any{it.contains("date:123")});assertTrue(texts.any{it.contains("不合并")});assertTrue(texts.any{it.contains("明文")})
  contentResolver.files["input"]=byteArrayOf(0);buttons.getValue("继续并选择安全备份位置").click();val safety=restoreSession.safetyRequestCode!!;onStop()
  handleRestoreSafetyResult(safety,RESULT_OK,result("safety"));settle();assertEquals(payload(),app.repository.rows);assertEquals(original,LearningBackupCodec.decode(contentResolver.files.getValue("safety")).data)
  assertEquals(listOf("input","safety"),contentResolver.reads);assertTrue(contentResolver.closed);assertEquals(1,app.repository.restores);assertFalse(restoreSession.busy);assertTrue(restoreButton!!.isEnabled)
 }
 fun emptyPreviewExplicitlyWarnsAndClearsOnlyAfterSafety(){showRestorePreview(frozen(true));assertTrue(texts.any{it.contains("空备份")&&it.contains("清空")});val id=readySafety(true);handleRestoreSafetyResult(id,RESULT_OK,result("safety"));settle();assertEquals(payload(true),app.repository.rows);assertTrue(contentResolver.files.containsKey("safety"))}
 fun previewCancelRetainsDatabaseAndAllowsRetry(){val id=restoreSession.beginImport()!!;restoreSession.beginReading(id,"input");restoreSession.freezeImport(id,frozen());showRestorePreview(frozen());buttons.getValue("取消").click();assertFalse(restoreSession.busy);assertEquals(0,app.repository.restores);startRestore();assertNotNull(restoreSession.importRequestCode)}
 @Test fun sameUriRefusedWithoutWriteOrApply(){val id=readySafety();handleRestoreSafetyResult(id,RESULT_OK,result("input"));settle();assertTrue(contentResolver.writes.isEmpty());assertEquals(0,app.repository.restores);assertFalse(restoreSession.busy)}
 @Test fun safetyCancelNoWriteAndNoSuccess(){val id=readySafety();handleRestoreSafetyResult(id,0,null);settle();assertEquals(0,app.repository.restores);assertTrue(contentResolver.writes.isEmpty());assertFalse(restoreSession.busy);assertTrue(restoreButton!!.isEnabled)}
 @Test fun duplicateAndWrongSafetyCallbacksApplyOnlyOnce(){val id=readySafety();handleRestoreSafetyResult(id+1,RESULT_OK,result("wrong"));handleRestoreSafetyResult(id,RESULT_OK,result("safety"));handleRestoreSafetyResult(id,RESULT_OK,result("duplicate"));settle();assertEquals(1,app.repository.restores);assertEquals(listOf("safety"),contentResolver.writes)}
 @Test fun failedWriteCloseFlushReadNullMismatchNeverAppliesAndCanRestart(){for(f in listOf("write","close","flush","read","nullin","nullout","mismatch")){val h=B4BackupActivityRegressionTest();val id=h.readySafety();h.contentResolver.apply{writeFailure=f=="write";closeFailure=f=="close";flushFailure=f=="flush";readFailure=f=="read";nullInput=f=="nullin";nullOutput=f=="nullout";mismatch=f=="mismatch"};val old=h.app.repository.rows;h.handleRestoreSafetyResult(id,h.RESULT_OK,h.result("safety"));h.settle();assertEquals(f,0,h.app.repository.restores);assertEquals(old,h.app.repository.rows);assertFalse(f,h.restoreSession.busy);assertTrue(h.restoreButton!!.isEnabled);assertFalse(h.messages.any{it.startsWith("已完整恢复")});assertNotNull(h.restoreSession.beginImport())}}
 @Test fun snapshotAndTransactionFailureReleaseBusy(){for(snapshot in listOf(true,false)){val h=B4BackupActivityRegressionTest();val id=h.readySafety();h.app.repository.failSnapshot=snapshot;h.app.repository.failRestore=!snapshot;h.handleRestoreSafetyResult(id,h.RESULT_OK,h.result("safety"));h.settle();assertEquals(0,h.app.repository.restores);assertFalse(h.restoreSession.busy);assertTrue(h.restoreButton!!.isEnabled)}}
 @Test fun revisionDriftUsesOriginalSnapshotRevisionAndRefusesOverwrite(){val id=readySafety();val old=app.repository.rows;contentResolver.afterClose={app.repository.changes.publishCommitted()};handleRestoreSafetyResult(id,RESULT_OK,result("safety"));settle();assertEquals(0L,app.repository.expected);assertEquals(old,app.repository.rows);assertEquals(0,app.repository.restores);assertTrue(messages.last().contains("新变化"));assertFalse(restoreSession.busy)}
 @Test fun destroyWhileWorkerQueuedBeforeTransactionMustNotOverwrite(){val id=readySafety();val old=app.repository.rows;handleRestoreSafetyResult(id,RESULT_OK,result("safety"));onDestroy();settle();assertEquals("destroy before transaction must not restore",0,app.repository.restores);assertEquals(old,app.repository.rows)}
 @Test fun destroyDuringSafetyIOBeforeTransactionMustNotOverwrite(){val id=readySafety();val old=app.repository.rows;contentResolver.afterClose={onDestroy()};handleRestoreSafetyResult(id,RESULT_OK,result("safety"));settle();assertEquals("destroy during safety I/O must not restore",0,app.repository.restores);assertEquals(old,app.repository.rows)}
 @Test fun clearingAndStartingNewSessionMustNotLetOldWorkerApply(){val id=readySafety();handleRestoreSafetyResult(id,RESULT_OK,result("safety"));restoreSession.clear();val next=restoreSession.beginImport()!!;settle();assertEquals("old apply cannot survive replaced session",0,app.repository.restores);assertEquals(next,restoreSession.importRequestCode)}
 @Test fun restorePickerLaunchFailureReleasesBusy(){pickerFailure=true;startRestore();assertFalse(restoreSession.busy);assertTrue(restoreButton!!.isEnabled)}
 fun safetyPickerLaunchFailureReleasesBusy(){val id=restoreSession.beginImport()!!;restoreSession.beginReading(id,"input");restoreSession.freezeImport(id,frozen());showRestorePreview(frozen());pickerFailure=true;buttons.getValue("继续并选择安全备份位置").click();assertFalse(restoreSession.busy);assertTrue(restoreButton!!.isEnabled)}
 fun invalidImportOrNullReadNeverShowsPreviewAndCanRetry(){for(bad in listOf(true,false)){val h=B4BackupActivityRegressionTest();h.startRestore();h.contentResolver.nullInput=!bad;h.contentResolver.files["input"]=byteArrayOf(0);h.handleRestoreImportResult(h.restoreSession.importRequestCode!!,h.RESULT_OK,h.result("input"));h.settle();assertNull(h.restorePreviewDialog);assertEquals(0,h.app.repository.restores);assertFalse(h.restoreSession.busy);assertTrue(h.restoreButton!!.isEnabled)}}
 fun importOldSuccessAfterStopCannotOpenPreview(){startRestore();val id=restoreSession.importRequestCode!!;contentResolver.files["input"]=LearningBackupCodec.encode(payload(),"v",1,0);handleRestoreImportResult(id,RESULT_OK,result("input"));onStop();settle();assertNull(restorePreviewDialog);assertFalse(restoreSession.busy)}
 fun lateImportFailureCannotClearNewPicker(){startRestore();val old=restoreSession.importRequestCode!!;contentResolver.files["input"]=byteArrayOf(0);handleRestoreImportResult(old,RESULT_OK,result("input"));onStop();startRestore();val fresh=restoreSession.importRequestCode!!;settle();assertEquals(fresh,restoreSession.importRequestCode);assertTrue(restoreSession.busy)}
 fun backupWritesFrozenBytesOnceAndReturnsToIdle(){exportLearningBackup();settle();val id=backupExportSession.fileRequestCode!!;val old=app.repository.rows;app.repository.rows=payload();onStop();handleBackupExportResult(id,RESULT_OK,result("output"));handleBackupExportResult(id,RESULT_OK,result("duplicate"));settle();assertEquals(old,LearningBackupCodec.decode(contentResolver.files.getValue("output")).data);assertFalse(backupExportSession.busy);assertEquals(listOf("output"),contentResolver.writes)}
 fun backupAllFailurePathsReleaseBusy(){for(mode in listOf("snapshot","picker","write","close","cancel")){val h=B4BackupActivityRegressionTest();h.app.repository.failSnapshot=mode=="snapshot";h.pickerFailure=mode=="picker";h.exportLearningBackup();h.settle();if(h.backupExportSession.busy){h.contentResolver.writeFailure=mode=="write";h.contentResolver.closeFailure=mode=="close";h.handleBackupExportResult(h.backupExportSession.fileRequestCode!!,if(mode=="cancel")0 else h.RESULT_OK,if(mode=="cancel")null else h.result("out"));h.settle()};assertFalse(mode,h.backupExportSession.busy);assertTrue(h.backupExportButton!!.isEnabled)}}
 fun uiMutualExclusionAndLostPdfGuard(){startRestore();exportLearningBackup();assertFalse(backupExportSession.busy);assertNull(restoreSession.beginImport());assertFalse(restoreButton!!.isEnabled);restoreSession.clear();lostExportRequestCode=123;startRestore();exportLearningBackup();assertFalse(restoreSession.busy);assertFalse(backupExportSession.busy)}
 @Test fun boundedEndlessProviderStopsWithoutUnboundedAllocation(){var count=0L;contentResolver.streamFactory={object:InputStream(){override fun read()=0;override fun read(b:ByteArray,off:Int,len:Int):Int{count+=len;return len}}};try{readBoundedBytes(Uri("endless"));fail("endless accepted")}catch(e:BackupFormatException){assertTrue(count<=LearningBackupCodec.MAX_FILE_BYTES+65536)}}
 class State{val arrays=mutableMapOf<String,IntArray>();fun putIntArray(k:String,v:IntArray){arrays[k]=v};fun getIntArray(k:String)=arrays[k]}
 val interruptedDocumentRequests=mutableSetOf<Int>();val pdfCleaned=mutableListOf<Uri>();val pdfSaved=mutableListOf<LearningExportSnapshot>()
 fun cleanLostPdf(u:Uri){pdfCleaned+=u};fun savePdf(u:Uri,s:LearningExportSnapshot,id:Int){pdfSaved+=s;exportSession.cancel(id)}
 fun documents(requestCode:Int,resultCode:Int,data:Intent?){
if (interruptedDocumentRequests.remove(requestCode)) {
                // Frozen backup/restore state does not survive recreation: never auto-apply from the
                // URI or touch any file; the reserved request code is now consumed for good.
                toast("上次的备份或恢复操作已中断，请重新开始；未改动任何文件")
                updateBackupButtons()
            } else if (lostExportRequestCode >= 0 && requestCode == lostExportRequestCode) {
                // Consume before IO: another recreation must not wait for this result again.
                lostExportRequestCode = -1
                if (resultCode != RESULT_OK) { toast("已取消导出"); updateExportButton() }
                else if (data?.data == null) { toast(PdfPlaceholderCleanup.Result.NO_URI.message); updateExportButton() }
                else cleanLostPdf(data.data!!)
            } else if (requestCode == exportSession.fileRequestCode) {
                val snapshot = exportSession.takeResult(requestCode) ?: return
                if (resultCode == RESULT_OK && data?.data != null) savePdf(data.data!!, snapshot, requestCode)
                else {
                    exportSession.cancel(requestCode); toast("已取消导出"); updateExportButton()
                }
            } else if (requestCode == backupExportSession.fileRequestCode) {
                handleBackupExportResult(requestCode, resultCode, data)
            } else if (requestCode == restoreSession.importRequestCode) {
                handleRestoreImportResult(requestCode, resultCode, data)
            } else if (requestCode == restoreSession.safetyRequestCode) {
                handleRestoreSafetyResult(requestCode, resultCode, data)
            }
 }
 fun saveState(outState:State){
        val pendingBackupRequests = ArrayList<Int>()
        backupExportSession.fileRequestCode?.let { pendingBackupRequests += it }
        restoreSession.importRequestCode?.let { pendingBackupRequests += it }
        restoreSession.safetyRequestCode?.let { pendingBackupRequests += it }
        pendingBackupRequests += interruptedDocumentRequests
        outState.putIntArray("backup_requests", pendingBackupRequests.toIntArray())

 }
 fun loadState(savedInstanceState:State?){
        savedInstanceState?.getIntArray("backup_requests")?.forEach { code ->
            if (code >= 0) { interruptedDocumentRequests += code; LearningExportSession.reserveRequestCode(code) }
        }

 }
 @Test fun savedPickerCodesRecreatedRejectAllUrisAndReserveAllocator(){
  val stages=listOf("backup","import","safety")
  for(stage in stages){val h=B4BackupActivityRegressionTest();val id=when(stage){"backup"->{h.backupExportSession.begin()!!.also{h.backupExportSession.freeze(it,byteArrayOf(1))}};"import"->h.restoreSession.beginImport()!!;else->h.readySafety()};val state=State();h.saveState(state);h.onDestroy();val new=B4BackupActivityRegressionTest();new.loadState(state);new.documents(id,new.RESULT_OK,new.result("old-uri"));new.documents(id,new.RESULT_OK,new.result("old-uri"));new.settle();assertEquals(0,new.app.repository.restores);assertTrue(new.contentResolver.reads.isEmpty());assertTrue(new.contentResolver.writes.isEmpty());assertTrue(new.pdfCleaned.isEmpty());assertEquals(1,new.messages.size);new.startRestore();assertTrue(new.restoreSession.importRequestCode!!>id)}
 }
 @Test fun interleavedDocumentResultsNeverCrossPdfBackupRestore(){
  exportLearningBackup();settle();val b=backupExportSession.fileRequestCode!!
  documents(b+991,RESULT_OK,result("unknown"));assertEquals(b,backupExportSession.fileRequestCode)
  documents(b,RESULT_OK,result("backup"));settle();assertEquals(listOf("backup"),contentResolver.writes);assertTrue(pdfCleaned.isEmpty())
  val id=readySafety();documents(b,RESULT_OK,result("late-backup"));assertEquals(id,restoreSession.safetyRequestCode);documents(id,RESULT_OK,result("safety"));settle();assertEquals(1,app.repository.restores)
 }
 @Test fun recreatedBackupCallbackNeverEntersLostPdfCleanup(){val state=State();state.putIntArray("backup_requests",intArrayOf(30001));loadState(state);lostExportRequestCode=30002;documents(30001,RESULT_OK,result("backup"));assertTrue(pdfCleaned.isEmpty());assertEquals(30002,lostExportRequestCode);documents(30002,RESULT_OK,result("pdf"));assertEquals(listOf(Uri("pdf")),pdfCleaned);assertTrue(contentResolver.writes.isEmpty())}
 @Test fun pdfFrozenBeforeRestoreKeepsOriginalWordsAndPersonalFields(){val entry=DictionaryEntry("old","n","historical","demo","v0");val w=LearnedWord(entry,entry.source,entry.version,"old",2,1,2,true,"frozen");val r=exportSession.begin("")!!;val frozen=LearningExportSnapshot(listOf(w),"",LocalDate.of(2026,10,16));assertTrue(exportSession.freeze(r,frozen));assertSame(frozen,exportSession.choose(r));val id=readySafety();documents(id,RESULT_OK,result("safety"));settle();documents(r.id,RESULT_OK,result("pdf"));assertSame(frozen,pdfSaved.single());assertEquals(w,pdfSaved.single().words.single());assertTrue(LearningExportText.row(0,w).contains("frozen"));assertEquals(1,app.repository.restores)}
 @Test fun cancelledImportViaActualRoutingNeverReads(){startRestore();val id=restoreSession.importRequestCode!!;documents(id,0,null);settle();assertFalse(restoreSession.busy);assertTrue(contentResolver.reads.isEmpty());assertTrue(contentResolver.writes.isEmpty())}

 // R2-only explicit queue/IO/repository fakes around unchanged exact production methods.
 @Test fun r2CancelledQueuedWorkerDoesNotEvenWriteSafetyBackup(){val id=readySafety();handleRestoreSafetyResult(id,RESULT_OK,result("safety"));onDestroy();settle();assertTrue(contentResolver.writes.isEmpty());assertEquals(0,app.repository.snapshots);assertEquals(0,app.repository.restores);assertTrue(messages.isEmpty())}
 @Test fun r2CancelledDuringSnapshotStillCannotEnterOverwrite(){val id=readySafety();handleRestoreSafetyResult(id,RESULT_OK,result("safety"));app.repository.atSnapshot={onDestroy()};settle();assertEquals(0,app.repository.restores);assertTrue(messages.isEmpty());assertTrue(contentResolver.files.containsKey("safety"))}
 @Test fun r2AdmittedTransactionMayFinishAtomicallyAfterDestroy(){val id=readySafety();val imported=payload();app.repository.atRestore={assertTrue(restoreSession.applyHandle!!.isAdmitted);onDestroy()};handleRestoreSafetyResult(id,RESULT_OK,result("safety"));settle();assertEquals(imported,app.repository.rows);assertEquals(1,app.repository.restores);assertEquals(1L,app.repository.changes.version);assertTrue(messages.isEmpty())}
 @Test fun r2DestroyAfterCommitBeforeMainCompletionDoesNotToastOrReopen(){val id=readySafety();handleRestoreSafetyResult(id,RESULT_OK,result("safety"));app.worker.one();assertEquals(1,app.repository.restores);onDestroy();main.all();assertTrue(messages.isEmpty());assertNull(restorePreviewDialog);assertFalse(restoreSession.busy)}
 @Test fun r2ErrorCompletionAfterDestroyDoesNotToast(){val id=readySafety();app.repository.failRestore=true;handleRestoreSafetyResult(id,RESULT_OK,result("safety"));app.worker.one();onDestroy();main.all();assertTrue(messages.isEmpty());assertEquals(0,app.repository.restores);assertFalse(restoreSession.busy)}
 @Test fun r2DuplicateSafetyCallbackIsSingleAdmissionAndSingleSuccess(){val id=readySafety();handleRestoreSafetyResult(id,RESULT_OK,result("safety"));val h=restoreSession.applyHandle!!;handleRestoreSafetyResult(id,RESULT_OK,result("duplicate"));assertEquals(1,app.worker.tasks.size);settle();assertTrue(h.isAdmitted);assertFalse(h.isActive());assertEquals(1,app.repository.restores);assertEquals(listOf("safety"),contentResolver.writes);assertEquals(1,messages.count{it.startsWith("已完整恢复")})}
 @Test fun r2ClearNewSessionDuringSafetyIoCannotAdmitOrClearNewBusy(){val id=readySafety();var newId:Int?=null;contentResolver.afterClose={restoreSession.clear();newId=restoreSession.beginImport()};handleRestoreSafetyResult(id,RESULT_OK,result("safety"));settle();assertEquals(0,app.repository.restores);assertEquals(newId,restoreSession.importRequestCode);assertTrue(restoreSession.busy);assertTrue(messages.isEmpty());assertFalse(restoreButton!!.isEnabled)}
 @Test fun r2OldCompletedWorkerCannotClearNewApplyingIdentity(){val id=readySafety();handleRestoreSafetyResult(id,RESULT_OK,result("safety"));app.worker.one();restoreSession.clear();val fresh=readySafety();handleRestoreSafetyResult(fresh,RESULT_OK,result("safety2"));val h=restoreSession.applyHandle;main.one();assertTrue(restoreSession.busy);assertSame(h,restoreSession.applyHandle);assertTrue(h!!.isActive());assertFalse(restoreButton!!.isEnabled);app.worker.all();main.all();assertEquals(2,app.repository.restores);assertFalse(restoreSession.busy)}
 @Test fun r2FailedSafetyWriteCanRetryCompleteRestoreNormally(){val old=app.repository.rows;val id=readySafety();contentResolver.closeFailure=true;handleRestoreSafetyResult(id,RESULT_OK,result("failed"));settle();assertEquals(old,app.repository.rows);assertFalse(restoreSession.busy);assertEquals(1,messages.size);contentResolver.closeFailure=false;val next=readySafety();handleRestoreSafetyResult(next,RESULT_OK,result("retry"));settle();assertEquals(1,app.repository.restores);assertEquals(payload(),app.repository.rows);assertEquals(2,messages.size);assertFalse(restoreSession.busy)}
 @Test fun r2RevisionDriftThenRetryKeepsNormalLifecycle(){val id=readySafety();contentResolver.afterClose={app.repository.changes.publishCommitted()};handleRestoreSafetyResult(id,RESULT_OK,result("drift"));settle();assertEquals(0,app.repository.restores);assertFalse(restoreSession.busy);assertTrue(messages.single().contains("未覆盖"));contentResolver.afterClose=null;val next=readySafety();handleRestoreSafetyResult(next,RESULT_OK,result("retry"));settle();assertEquals(1,app.repository.restores);assertFalse(restoreSession.busy)}
 @Test fun r2LongSafetyIoDoesNotHoldUiCancellationMonitor(){
  val id=readySafety();val inside=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1);val cleared=java.util.concurrent.CountDownLatch(1);val error=java.util.concurrent.atomic.AtomicReference<Throwable>()
  contentResolver.afterClose={inside.countDown();if(!release.await(5,java.util.concurrent.TimeUnit.SECONDS))error("IO rendezvous timeout")}
  handleRestoreSafetyResult(id,RESULT_OK,result("safety"));val task=app.worker.tasks.removeFirst();val worker=Thread{try{task()}catch(t:Throwable){error.set(t)}};worker.start();assertTrue(inside.await(5,java.util.concurrent.TimeUnit.SECONDS))
  val ui=Thread{try{onDestroy()}catch(t:Throwable){error.set(t)}finally{cleared.countDown()}};ui.start();val cancelledBeforeIoCompleted=cleared.await(2,java.util.concurrent.TimeUnit.SECONDS);release.countDown();worker.join(5000);ui.join(5000);main.all()
  assertTrue("destroy should not wait for safety provider",cancelledBeforeIoCompleted);assertFalse(worker.isAlive);assertFalse(ui.isAlive);assertNull(error.get());assertEquals(0,app.repository.restores);assertTrue(messages.isEmpty())
 }

 @Test fun legacyPreviewExplicitlyWarnsStudyWillReset(){
  val backup=LearningBackup("0.4.1",11,1,0,payload(),1)
  val id=restoreSession.beginImport()!!;restoreSession.beginReading(id,"input");restoreSession.freezeImport(id,backup)
  showRestorePreview(backup)
  assertTrue(texts.any{it.contains("旧版格式（v1）")});assertTrue(texts.any{it.contains("背词进度为空")});assertTrue(texts.any{it.contains("默认值")})
 }
 @Test fun v2SafetyBackupPreservesWholeStudyPayloadBeforeOverwrite(){
  val now=1000000L
  app.repository.rows=LearningBackupData(listOf(BackupWord("x","x","n","old","s","v",true,"note")),emptyList(),BackupStudySettings("cet6",41),listOf(BackupStudyState("x","ky",0,now+86400000,now,now,1)),listOf(BackupStudyReview("r","x","know",-1,0,now+86400000,now,"1970-01-01","UTC",true,1)))
  val before=app.repository.rows;val id=readySafety();handleRestoreSafetyResult(id,RESULT_OK,result("safe"));settle()
  assertEquals(before,LearningBackupCodec.decode(contentResolver.files.getValue("safe")).data);assertEquals(1,app.repository.restores)
 }
}
