package com.shici.app.data.b4

import android.app.Activity
import android.app.AlertDialog
import android.app.Instrumentation
import android.content.Intent
import android.content.IntentFilter
import android.graphics.*
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.view.ViewGroup
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.*
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.shici.app.ShiciApplication
import com.shici.app.core.*
import com.shici.app.data.*
import com.shici.app.ui.MainActivity
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.google.android.gms.tasks.Tasks
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Actual installed MainActivity/StudyScreen callbacks, routed ONLY to an explicitly injected UUID DB.
 * The original lazy delegate is restored after all workers drain; learning.db is never opened by this test.
 * Controlled-document tests replace only the external picker result, not any application handler.
 */
class B4DeviceUiFunctionalTest {
 private val ins=InstrumentationRegistry.getInstrumentation()
 private val ctx=ins.targetContext
 private val app=ctx.applicationContext as ShiciApplication
 private val delegate=ShiciApplication::class.java.getDeclaredField("repository\$delegate").apply{isAccessible=true}
 private lateinit var old:Any
 private lateinit var repo:LearningRepository
 private lateinit var name:String
 private lateinit var mainHash:ByteArray
 private var preserveUuid=false
 private var scenario:ActivityScenario<MainActivity>?=null
 private val ownFiles=mutableListOf<File>()
 private fun hashMain()=MessageDigest.getInstance("SHA-256").digest(ctx.getDatabasePath("learning.db").readBytes())
 private val out get()=File(ctx.filesDir,"device-functional-evidence").apply{mkdirs()}
 @Before fun isolatedRouteBeforeAnyActivity() {
  ins.uiAutomation.serviceInfo=ins.uiAutomation.serviceInfo.apply{flags=flags or android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS}
  mainHash=hashMain();name="device-ui-${UUID.randomUUID()}.db";repo=LearningRepository(ctx,name)
  assertNotEquals(ctx.getDatabasePath("learning.db").canonicalPath,repo.writableDatabase.path)
  old=delegate.get(app);delegate.set(app,lazyOf(repo));assertSame(repo,app.repository)
  assertEquals(ctx.getDatabasePath(name).canonicalPath,File(app.repository.writableDatabase.path).canonicalPath)
 }
 @After fun restoreOriginalRouteWithoutCopyingAnyUserDatabase() {
  try {ins.runOnMainSync{com.shici.app.capture.CaptureStatus.stop(ctx)};scenario?.close();scenario=null;settle();assertArrayEquals("user learning.db changed",mainHash,hashMain())}
  finally {delegate.set(app,old);repo.close();if(!preserveUuid)ctx.deleteDatabase(name);ownFiles.forEach{it.delete()}}
 }
 private fun settle(){repeat(3){app.worker.submit{}.get(15,TimeUnit.SECONDS);ins.waitForIdleSync()}}
 private fun launch(){scenario=ActivityScenario.launch(MainActivity::class.java);settle();assertSame(repo,app.repository)}
 private fun descendants(v:View):List<View> = listOf(v)+if(v is ViewGroup)(0 until v.childCount).flatMap{descendants(v.getChildAt(it))}else emptyList()
 private fun activityViews(a:MainActivity)=descendants(a.window.decorView)
 private fun labels():List<String>{var l=emptyList<String>();scenario!!.onActivity{a->l=activityViews(a).filterIsInstance<TextView>().map{it.text.toString()}};return l}
 private fun waitUntil(message:String,f:()->Boolean){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){settle();if(f())return;Thread.sleep(80)};fail(message+" labels="+labels().joinToString("|"))}
 private fun click(text:String){waitUntil("missing button $text"){var b:Button?=null;scenario!!.onActivity{a->b=activityViews(a).filterIsInstance<Button>().firstOrNull{it.text.toString()==text && it.isEnabled}};b!=null};scenario!!.onActivity{a->val b=activityViews(a).filterIsInstance<Button>().first{it.text.toString()==text && it.isEnabled};assertTrue(b.performClick())};settle()}
 private fun capture(id:String){Thread.sleep(350);ins.waitForIdleSync();ins.uiAutomation.takeScreenshot()?.let{b->File(out,"$id.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()};File(out,"$id-labels.txt").writeText(labels().joinToString("\n"))}
 private fun seed(k:String="abandon",note:String="device note") {assertTrue(repo.record(QueryEvent("fixture-$k",k,k,k,System.currentTimeMillis()),DictionaryEntry(k,"n","synthetic meaning $k","device-fixture","v0")));repo.updateWord(k,true,note)}
 private fun dialog(field:String):AlertDialog {var d:AlertDialog?=null;scenario!!.onActivity{a->d=MainActivity::class.java.getDeclaredField(field).apply{isAccessible=true}.get(a) as AlertDialog?};return d?:error("no $field")}
 private fun clickDialog(field:String,text:String){waitUntil("dialog $field"){runCatching{dialog(field).isShowing}.getOrDefault(false)};scenario!!.onActivity{val d=dialog(field);assertTrue(descendants(d.window!!.decorView).filterIsInstance<Button>().first{it.text.toString()==text}.performClick())};settle()}
 private fun nodes(n:AccessibilityNodeInfo?):List<AccessibilityNodeInfo> = if(n==null)emptyList()else listOf(n)+(0 until n.childCount).flatMap{nodes(n.getChild(it))}
 private fun allNodes():List<AccessibilityNodeInfo> = ins.uiAutomation.windows.flatMap{nodes(it.root)}+nodes(ins.uiAutomation.rootInActiveWindow)
 private fun systemClick(text:String){val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(10);while(System.nanoTime()<end){val n=allNodes().firstOrNull{it.text?.toString()==text && it.isClickable};if(n!=null){assertTrue(n.performAction(AccessibilityNodeInfo.ACTION_CLICK));settle();return};Thread.sleep(100)};fail("system button not found: $text")}
 private fun fixtureFile(suffix:String)=File(ctx.cacheDir,"device-${UUID.randomUUID()}$suffix").also{ownFiles+=it}
 private fun picker(action:String,file:File):Instrumentation.ActivityMonitor = ins.addMonitor(IntentFilter(action).apply{addCategory(Intent.CATEGORY_OPENABLE);addDataType("*/*")},Instrumentation.ActivityResult(Activity.RESULT_OK,Intent().setData(Uri.fromFile(file))),true)

 @Test fun actualStudyButtonsAllBooksRevealFeedbackGoalAndActivityRecreation(){
  seed();launch();click("背词");waitUntil("ky pending"){repo.backupSnapshot().data.session!=null}
  assertEquals(StudyBook.KAOYAN,repo.studySettings().selectedBook)
  click("六级");assertEquals(StudyBook.CET6,repo.studySettings().selectedBook)
  click("个人生词");assertEquals(StudyBook.PERSONAL,repo.studySettings().selectedBook)
  waitUntil("personal card"){repo.backupSnapshot().data.session?.entryKey=="abandon"}
  val token=repo.backupSnapshot().data.session!!.token
  click("显示答案");assertTrue(repo.backupSnapshot().data.session!!.answerRevealed)
  assertTrue(labels().any{it.contains("device-fixture")});assertTrue(labels().contains("device note"))
  capture("study-revealed")
  scenario!!.recreate();settle();assertEquals(token,repo.backupSnapshot().data.session!!.token);assertTrue(repo.backupSnapshot().data.session!!.answerRevealed)
  click("认识");waitUntil("real feedback commit"){repo.backupSnapshot().data.reviews.size==1}
  assertEquals(1,repo.backupSnapshot().data.states.size);assertEquals(1,repo.events().size)
  click("+5");assertEquals(25,repo.studySettings().dailyNewGoal)
  scenario!!.close();scenario=null;launch();click("背词");assertEquals(StudySettings(StudyBook.PERSONAL,25),repo.studySettings());assertNull(repo.backupSnapshot().data.session)
  assertTrue(labels().any{it.contains("今日已反馈 1 次")});capture("study-restarted-progress")
 }
 @Test fun realPersonalDetailSaveAndConfirmedDeleteOnlyFixture(){
  seed();seed("ability","untouched note");val keep=repo.backupSnapshot().data.words.first{it.entryKey=="ability"}
  launch();click("生词本");waitUntil("words loaded"){labels().contains("abandon")}
  scenario!!.onActivity{a->var v:View=activityViews(a).filterIsInstance<TextView>().first{it.text.toString()=="abandon"};while(!v.isClickable)v=v.parent as View;assertTrue(v.performClick())};settle()
  // On a wide tablet details are inline; on a compact device use the actual app detail dialog.
  scenario!!.onActivity{a->val all=activityViews(a)+runCatching{descendants(dialog("activeDialog").window!!.decorView)}.getOrDefault(emptyList());val note=all.filterIsInstance<EditText>().first{it.minLines>=3};note.setText("saved through actual button")}
  click("保存笔记与状态");assertEquals("saved through actual button",repo.backupSnapshot().data.words.first{it.entryKey=="abandon"}.note)
  click("删除生词");systemClick("删除");waitUntil("confirmed delete"){repo.backupSnapshot().data.words.none{it.entryKey=="abandon"}}
  assertEquals(keep,repo.backupSnapshot().data.words.single());assertTrue(repo.events().all{it.entryKey=="ability"});capture("deleted-fixture-only")
 }
 @Test fun actualBackupRestoreAndPdfButtonsWithControlledDocumentResults(){
  seed();launch();click("设置");val backup=fixtureFile(".shicibak");var monitor=picker(Intent.ACTION_CREATE_DOCUMENT,backup)
  try{click("导出学习数据备份");waitUntil("backup written"){backup.exists() && backup.length()>32}}finally{ins.removeMonitor(monitor)}
  val frozen=LearningBackupCodec.decode(backup.readBytes()).data;assertEquals(repo.backupSnapshot().data,frozen)
  seed("ability","new after backup");val safetyExpected=repo.backupSnapshot().data
  monitor=picker(Intent.ACTION_OPEN_DOCUMENT,backup)
  try{click("从备份完整恢复");waitUntil("restore preview"){runCatching{dialog("restorePreviewDialog").isShowing}.getOrDefault(false)}}finally{ins.removeMonitor(monitor)}
  val safety=fixtureFile(".safety");monitor=picker(Intent.ACTION_CREATE_DOCUMENT,safety)
  try{clickDialog("restorePreviewDialog","继续并选择安全备份位置");waitUntil("restore committed"){repo.backupSnapshot().data==frozen}}finally{ins.removeMonitor(monitor)}
  assertEquals(safetyExpected,LearningBackupCodec.decode(safety.readBytes()).data)
  File(out,"controlled-backup.shicibak").writeBytes(backup.readBytes());File(out,"controlled-safety.shicibak").writeBytes(safety.readBytes())
  click("生词本");click("导出 PDF");val pdf=fixtureFile(".pdf");monitor=picker(Intent.ACTION_CREATE_DOCUMENT,pdf)
  try{clickDialog("exportDialog","选择保存位置");waitUntil("PDF written"){pdf.exists() && pdf.length()>100}}finally{ins.removeMonitor(monitor)}
  assertEquals("%PDF-",pdf.inputStream().use{String(it.readNBytes(5))});File(out,"controlled-export.pdf").writeBytes(pdf.readBytes())
  android.graphics.pdf.PdfRenderer(android.os.ParcelFileDescriptor.open(pdf,android.os.ParcelFileDescriptor.MODE_READ_ONLY)).use{renderer->assertTrue(renderer.pageCount>=1);val b=Bitmap.createBitmap(595,842,Bitmap.Config.ARGB_8888);b.eraseColor(Color.WHITE);renderer.openPage(0).use{it.render(b,null,null,android.graphics.pdf.PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)};File(out,"controlled-pdf-render.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)};b.recycle()}
  assertEquals(frozen,repo.backupSnapshot().data);capture("export-return")
 }
 @Test fun bundledOcrRecognizesOnlySyntheticBitmapAndDictionaryResolves(){
  val b=Bitmap.createBitmap(1200,400,Bitmap.Config.ARGB_8888);val canvas=Canvas(b);canvas.drawColor(Color.WHITE);val p=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=100f;typeface=Typeface.create("sans-serif",Typeface.NORMAL)};canvas.drawText("evidence",80f,210f,p)
  val recognizer=TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
  try {val result=Tasks.await(recognizer.process(InputImage.fromBitmap(b,0)),30,TimeUnit.SECONDS);assertTrue(result.text.lowercase().contains("evidence"));assertTrue(app.dictionary.getOrThrow().lookup("evidence") is LookupResult.Match);assertTrue(repo.events().isEmpty());File(out,"synthetic-ocr.png").outputStream().use{b.compress(Bitmap.CompressFormat.PNG,100,it)}}finally{recognizer.close();b.recycle()}
 }

 @Test fun prepareActualProcessRestartFixture() {
  val requested=InstrumentationRegistry.getArguments().getString("restart_db")?:error("restart_db required")
  require(requested.matches(Regex("device-restart-[0-9a-f-]{36}\\.db")))
  repo.close();ctx.deleteDatabase(name);name=requested;repo=LearningRepository(ctx,name);delegate.set(app,lazyOf(repo))
  preserveUuid=true
  seed();seed("ability");launch();click("背词");click("个人生词");click("+5");click("显示答案");click("认识");waitUntil("one committed answer"){repo.backupSnapshot().data.reviews.size==1};click("显示答案")
  val d=repo.backupSnapshot().data;assertTrue(d.session!!.answerRevealed)
  File(out,"restart-expected.shicibackup").writeBytes(LearningBackupCodec.encode(d,"1.0.3",15,System.currentTimeMillis()))
  File(out,"restart-prepare-pid.txt").writeText(android.os.Process.myPid().toString());capture("before-process-death")
 }
 @Test fun verifyActualNewProcessRestoresSamePendingRevealAndFeedback() {
  val requested=InstrumentationRegistry.getArguments().getString("restart_db")?:error("restart_db required")
  require(requested.matches(Regex("device-restart-[0-9a-f-]{36}\\.db")))
  repo.close();ctx.deleteDatabase(name);name=requested;repo=LearningRepository(ctx,name);delegate.set(app,lazyOf(repo))
  val expected=LearningBackupCodec.decode(File(out,"restart-expected.shicibackup").readBytes()).data
  assertNotEquals(File(out,"restart-prepare-pid.txt").readText().toInt(),android.os.Process.myPid())
  assertEquals(expected,repo.backupSnapshot().data)
  launch();click("背词");assertEquals(expected.session!!.token,repo.backupSnapshot().data.session!!.token)
  assertTrue(labels().contains("认识"));assertTrue(labels().any{it.contains("今日已反馈 1 次")});assertEquals(25,repo.studySettings().dailyNewGoal)
  capture("after-new-process-resume");click("模糊");waitUntil("second real feedback"){repo.backupSnapshot().data.reviews.size==2};assertEquals(listOf(1L,2L),repo.backupSnapshot().data.reviews.map{it.seq})
 }
 @Test fun actualForgetAndFuzzyButtonsPersistCorrectIntervalsWithoutQueries(){
  seed();seed("ability");launch();click("背词");click("个人生词");click("显示答案");click("忘记");waitUntil("forget commit"){repo.backupSnapshot().data.reviews.size==1}
  val forget=repo.backupSnapshot().data.reviews.single();assertEquals(600000L,forget.dueAfter-forget.reviewedAt)
  click("显示答案");click("模糊");waitUntil("fuzzy commit"){repo.backupSnapshot().data.reviews.size==2};val fuzzy=repo.backupSnapshot().data.reviews.last();assertEquals(StudyRules.DAY_MILLIS,fuzzy.dueAfter-fuzzy.reviewedAt)
  assertEquals(2,repo.events().size);assertEquals(2,repo.backupSnapshot().data.states.size);capture("fuzzy-forget-progress")
 }

 @Test fun actualManualQueryButtonUsesBundledDictionaryAndRecordsOnlyUuid(){
  launch();click("设置");scenario!!.onActivity{a->activityViews(a).filterIsInstance<EditText>().first{it.hint?.toString()?.contains("evidence")==true}.setText("evidence")};click("查询原始词库")
  waitUntil("manual query recorded"){repo.events().size==1};assertEquals("evidence",repo.events().single().entryKey);assertEquals(app.dictionary.getOrThrow().entry("evidence")!!.meaning,repo.words().single().entry.meaning)
  capture("manual-query-fixture");click("生词本");assertTrue(labels().contains("evidence"));click("统计");assertTrue(labels().any{it.contains("查询")});capture("fixture-statistics")
 }
 @Test fun realSystemSafCreatesUniqueBackupAndPdfFromIsolatedUi(){
  seed();launch();click("设置");val prefix="df-${UUID.randomUUID().toString().take(8)}"
  File(out,"real-saf-prefix.txt").writeText(prefix)
  click("导出学习数据备份");saveSystemDocument(prefix)
  waitUntil("backup picker result settled"){var busy=true;scenario!!.onActivity{a->val f=MainActivity::class.java.getDeclaredField("backupExportSession").apply{isAccessible=true};busy=(f.get(a) as LearningBackupExportSession).busy};!busy}
  val frozen=repo.backupSnapshot().data;seed("ability","not in imported backup");val safetyExpected=repo.backupSnapshot().data
  click("从备份完整恢复");openOwnSystemDocument(prefix+".bin")
  waitUntil("actual SAF import preview"){runCatching{dialog("restorePreviewDialog").isShowing}.getOrDefault(false)}
  clickDialog("restorePreviewDialog","继续并选择安全备份位置");saveSystemDocument(prefix+"-safe")
  waitUntil("actual SAF restore committed"){repo.backupSnapshot().data==frozen}
  File(out,"real-saf-expected-before-safety.shicibackup").writeBytes(LearningBackupCodec.encode(safetyExpected,"1.0.3",15,System.currentTimeMillis()))
  click("生词本");click("导出 PDF");clickDialog("exportDialog","选择保存位置");saveSystemDocument(prefix)
  waitUntil("PDF result settled"){var busy=true;scenario!!.onActivity{a->val f=MainActivity::class.java.getDeclaredField("exportSession").apply{isAccessible=true};busy=(f.get(a) as LearningExportSession).busy};!busy}
  assertEquals(1,repo.words().size);capture("real-saf-return")
 }
 private fun clickNodeOrClickableParent(node:AccessibilityNodeInfo){
  var n:AccessibilityNodeInfo?=node
  while(n!=null && !n.isClickable)n=n.parent
  assertNotNull("clickable ancestor for ${node.viewIdResourceName}",n);assertTrue(n!!.performAction(AccessibilityNodeInfo.ACTION_CLICK))
 }
 private fun saveSystemDocument(filename:String){
  val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);var field:AccessibilityNodeInfo?=null
  var openedDownload=false;var openedSave=false
  while(System.nanoTime()<end && field==null){
   val ns=nodes(ins.uiAutomation.rootInActiveWindow)
   field=ns.firstOrNull{it.isEditable && it.isEnabled && it.packageName?.toString()=="com.coloros.filemanager"}
   if(field==null && !openedDownload){val download=ns.firstOrNull{it.viewIdResourceName=="com.coloros.filemanager:id/action_download"};if(download!=null){clickNodeOrClickableParent(download);openedDownload=true;Thread.sleep(400)}}
   if(field==null && openedDownload && !openedSave){val save=ns.firstOrNull{it.isClickable && it.isEnabled && it.text?.toString() in listOf("保存","保存到此","保存到此处")};if(save!=null){assertTrue(save.performAction(AccessibilityNodeInfo.ACTION_CLICK));openedSave=true;Thread.sleep(400)}}
   if(field==null)Thread.sleep(100)
  }
  if(field==null){
   val root=ins.uiAutomation.rootInActiveWindow
   File(out,"saf-structural-failure.txt").writeText(nodes(root).joinToString("\n"){"${it.packageName} ${it.className} ${it.viewIdResourceName} editable=${it.isEditable} text="+(if(it.isEditable || it.className?.toString()=="android.widget.Button" || it.text?.toString() in listOf("保存","Save","SAVE","允许","取消"))it.text else "<redacted>")})
  }
  assertNotNull("real system picker editable filename",field)
  assertTrue(field!!.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,filename)}))
  Thread.sleep(120);assertTrue("filename must remain exact",nodes(ins.uiAutomation.rootInActiveWindow).any{it.isEditable && it.text?.toString()==filename})
  val choices=nodes(ins.uiAutomation.rootInActiveWindow).filter{it.isClickable && it.isEnabled && (it.text?.toString() in listOf("保存","Save","SAVE"))};assertTrue("real SAF save button",choices.isNotEmpty());assertTrue(choices.last().performAction(AccessibilityNodeInfo.ACTION_CLICK));settle()
 }

 @Test fun actualHomeCaptureSwitchCancelAndCloseReleaseWithoutPermissionBypass(){
  launch();assertEquals(com.shici.app.capture.CapturePhase.CLOSED,com.shici.app.capture.CaptureStatus.state.phase)
  scenario!!.onActivity{a->val sw=activityViews(a).filterIsInstance<Switch>().first{it.contentDescription?.toString()=="开启或关闭悬浮查词"};assertFalse(sw.isChecked);sw.performClick()}
  systemClick("取消");assertEquals(com.shici.app.capture.CapturePhase.CLOSED,com.shici.app.capture.CaptureStatus.state.phase)
  click("设置");assertTrue(labels().contains("悬浮查词已关闭"));assertEquals(com.shici.app.capture.CapturePhase.CLOSED,com.shici.app.capture.CaptureStatus.state.phase);assertTrue(repo.backupSnapshot().data.events.isEmpty())
 }

 private fun openOwnSystemDocument(filename:String){
  val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);var searched=false;var typed=false
  while(System.nanoTime()<end){
   val ns=nodes(ins.uiAutomation.rootInActiveWindow)
   val file=ns.firstOrNull{it.text?.toString() in listOf(filename,filename.removeSuffix(".bin")) && it.viewIdResourceName?.contains("file_list_item_title")==true}
   if(file!=null){clickNodeOrClickableParent(file);settle();return}
   if(!searched){val n=ns.firstOrNull{it.viewIdResourceName=="com.coloros.filemanager:id/action_search"};if(n!=null){clickNodeOrClickableParent(n);searched=true;Thread.sleep(150)}}
   if(searched && !typed){val e=ns.firstOrNull{it.isEditable && it.packageName?.toString()=="com.coloros.filemanager"};if(e!=null){assertTrue(e.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,Bundle().apply{putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,filename.removeSuffix(".bin"))}));typed=true;ins.sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_ENTER)}}
   Thread.sleep(120)
  }
  File(out,"saf-import-structural.txt").writeText(allNodes().joinToString("\n"){"${it.packageName} ${it.className} ${it.viewIdResourceName} clickable=${it.isClickable} text="+(if(it.text?.toString()?.startsWith("df-")==true || it.className?.toString()=="android.widget.Button")it.text else "<redacted>")});fail("own SAF document not found")
 }
 private fun findNode(description:String):AccessibilityNodeInfo {val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(15);while(System.nanoTime()<end){allNodes().firstOrNull{it.contentDescription?.toString()==description}?.let{return it};Thread.sleep(100)};error("missing own node: $description")}
 @Test fun realProjectionBubbleStrokeOcrAndCollectionUseOnlySyntheticScreen(){
  org.junit.Assume.assumeTrue("pre-existing overlay/notification permissions required; do not alter",android.provider.Settings.canDrawOverlays(ctx) && ctx.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED)
  launch();scenario!!.onActivity{a->activityViews(a).filterIsInstance<Switch>().first{it.contentDescription?.toString()=="开启或关闭悬浮查词"}.performClick()};systemClick("继续")
  var box=android.graphics.RectF();var pictureWidth=0;var pictureHeight=0
  scenario!!.onActivity{a->a.setContentView(object:View(a){val paint=Paint(Paint.ANTI_ALIAS_FLAG).apply{color=Color.BLACK;textSize=140f;typeface=Typeface.create("sans-serif",Typeface.NORMAL)}
   override fun onDraw(c:Canvas){c.drawColor(Color.WHITE);val x=width*.35f;val baseline=height*.45f;c.drawText("evidence",x,baseline,paint);val loc=IntArray(2);getLocationOnScreen(loc);box=android.graphics.RectF(x+loc[0],baseline+paint.ascent()+loc[1],x+paint.measureText("evidence")+loc[0],baseline+paint.descent()+loc[1])}
  })};ins.waitForIdleSync()
  val end=System.nanoTime()+TimeUnit.SECONDS.toNanos(12);var consented=false
  while(System.nanoTime()<end && !consented){val ns=nodes(ins.uiAutomation.rootInActiveWindow);val start=ns.firstOrNull{it.isClickable && it.isEnabled && it.packageName?.toString()=="com.android.systemui" && it.text?.toString() in listOf("立即开始","开始","Start now","Start","START NOW")};if(start!=null){assertTrue(start.performAction(AccessibilityNodeInfo.ACTION_CLICK));consented=true}else Thread.sleep(100)}
  assertTrue("normal system MediaProjection consent",consented)
  waitUntil("real CaptureService active"){com.shici.app.capture.CaptureStatus.state.phase==com.shici.app.capture.CapturePhase.ACTIVE}
  Thread.sleep(600);val source=ins.uiAutomation.takeScreenshot()!!;pictureWidth=source.width;pictureHeight=source.height;File(out,"capture-synthetic-source.png").outputStream().use{source.compress(Bitmap.CompressFormat.PNG,100,it)};source.recycle()
  assertTrue(box.width()>100);assertTrue(findNode("拾词：点击框选查词，拖动改变位置").performAction(AccessibilityNodeInfo.ACTION_CLICK))
  val selection=findNode("临时截图；沿文字涂划，可分多笔；点击完成划选才查询");val bounds=android.graphics.Rect();selection.getBoundsInScreen(bounds)
  val img=SelectionGeometry.imageBox(pictureWidth,pictureHeight,bounds.width(),bounds.height());val scale=(img.right-img.left)/pictureWidth
  val x1=bounds.left+img.left+(box.left+8)*scale;val x2=bounds.left+img.left+(box.right-8)*scale;val y=bounds.top+img.top+box.centerY()*scale
  val down=android.os.SystemClock.uptimeMillis()
  fun event(action:Int,x:Float,at:Long){val e=android.view.MotionEvent.obtain(down,at,action,x,y,0);e.source=android.view.InputDevice.SOURCE_TOUCHSCREEN;assertTrue(ins.uiAutomation.injectInputEvent(e,true));e.recycle()}
  event(android.view.MotionEvent.ACTION_DOWN,x1,down);for(i in 1..20){event(android.view.MotionEvent.ACTION_MOVE,x1+(x2-x1)*i/20,down+i*15)};event(android.view.MotionEvent.ACTION_UP,x2,down+330)
  systemClick("完成划选");waitUntil("real projection OCR collection"){repo.events().any{it.entryKey=="evidence"}}
  assertEquals(1,repo.events().size);assertEquals("evidence",repo.words().single().entry.key);assertEquals(app.dictionary.getOrThrow().entry("evidence")!!.meaning,repo.words().single().entry.meaning)
  File(out,"capture-flow-result.png").outputStream().use{ins.uiAutomation.takeScreenshot()!!.compress(Bitmap.CompressFormat.PNG,100,it)}
  scenario!!.recreate();settle();scenario!!.onActivity{a->activityViews(a).filterIsInstance<Switch>().first{it.contentDescription?.toString()=="开启或关闭悬浮查词"}.apply{assertTrue(isChecked);performClick()}}
  waitUntil("capture and floating overlay released"){com.shici.app.capture.CaptureStatus.state.phase==com.shici.app.capture.CapturePhase.CLOSED}
  assertFalse(nodes(ins.uiAutomation.rootInActiveWindow).any{it.contentDescription?.toString()=="拾词：点击框选查词，拖动改变位置"})
 }
}
