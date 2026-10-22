package com.shici.app.core.b4.uihost
import com.shici.app.core.*
import com.shici.app.core.b4.host.LearningRepository
import com.shici.app.core.b4.host.StudyMembership
import com.shici.app.core.b4.host.StudyCommitOutcome
import java.time.*
import java.time.format.DateTimeFormatter
/** Test clock substitutes only System.currentTimeMillis in the otherwise verbatim UI. */
object System {var now=java.lang.System.currentTimeMillis();fun currentTimeMillis()=now}
typealias Activity = Context
open class Context {open var isDestroyed=false;open var deadWrites=0}
open class Node(val context:Context,var textValue:String="") {
 val children=mutableListOf<Node>();var background:Any?=null;var gravity=0;var action:(()->Unit)?=null
 fun removeAllViews(){if(context.isDestroyed)context.deadWrites++;children.clear()}
 fun addView(n:Node,p:Any?=null){if(context.isDestroyed)context.deadWrites++;children+=n}
 fun setPadding(a:Int,b:Int,c:Int,d:Int){};fun setTextColor(c:Int){}
 fun all():List<Node> = listOf(this)+children.flatMap{it.all()}
 fun click(){action!!.invoke()}
}
class Button(c:Context,s:String):Node(c,s)
class LinearLayout(c:Context):Node(c){class LayoutParams(a:Int,b:Int,c:Float){var marginEnd=0}}
fun Context.column()=LinearLayout(this);fun Context.row()=LinearLayout(this)
fun Context.dp(n:Int)=n;fun Context.shape(c:Int,r:Int,b:Boolean=false):Any=c
fun Context.text(s:String,size:Float=16f,color:Int=0,bold:Boolean=false)=Node(this,s)
fun Context.button(s:String,primary:Boolean=false,f:()->Unit)=Button(this,s).apply{action=f}
fun Node.space(n:Int){};fun Node.divider(){}
object Gravity{const val CENTER=1;const val CENTER_VERTICAL=2}
object Color{const val WHITE=0};object Paper{const val green=1;const val ink=2;const val secondary=3;const val error=4;const val surface=5;const val warning=6}
class Queue {val tasks=java.util.ArrayDeque<()->Unit>();fun execute(f:()->Unit){tasks.add(f)};fun post(f:()->Unit){tasks.add(f)};fun one(){tasks.removeFirst().invoke()};fun all(){while(tasks.isNotEmpty())one()}}
class Looper{companion object{fun getMainLooper()=Looper()}}
class Handler(l:Looper){companion object{var queue=Queue()};fun post(f:()->Unit)=queue.post(f)}
class ShiciApplication(val repository:LearningRepository) {
 val worker=Queue();var dictionary:Result<RawKajwebDictionary> = Result.failure(IllegalStateException("not loaded"));var studyBooks:Result<StudyBooks> = Result.failure(IllegalStateException("not loaded"))
    fun resolveEntry(key: String): DictionaryEntry? =
        DictionaryResolver.resolve(dictionary.getOrNull(), repository.storedEntry(key), key) {
            repository.userEntry(it)
        }

}
class Clearable{fun stop(){};fun clear(){};fun next(){}}
class Dialog{fun dismiss(){}}
open class ActivityBase(val ctx:Context):Context(){override var isDestroyed:Boolean get()=ctx.isDestroyed;set(v){ctx.isDestroyed=v};override var deadWrites:Int get()=ctx.deadWrites;set(v){ctx.deadWrites=v};open fun onDestroy(){isDestroyed=true}}
class ActivityOwner(ctx:Context, private val app:ShiciApplication, private val rerender:()->Unit={},private val message:(String)->Unit={}):ActivityBase(ctx){
 val refreshGate=Clearable();val exportSession=Clearable();val manualGate=Clearable();val backupExportSession=Clearable();val restoreSession=Clearable()
 var restorePreviewDialog:Dialog?=null;var activeDialog:Dialog?=null
 fun render(){rerender()};fun toast(s:String){message(s)}
 fun screenForTest()=studyScreen
 fun screenInitializedForTest()=studyScreenDelegate.isInitialized()
    private val studyScreenDelegate = lazy { StudyScreen(app, this, { render() }, { toast(it) }) }
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
}
/**
 * The real 背词 review surface. It draws from the repository's persisted study round and never keeps a
 * parallel in-memory ledger: a committed feedback is re-read, not patched. There is one unified
 * entrance: due reviews first, then new words from the personal query set (recent first) followed by
 * the fixed ky/cet6 books stably interleaved and deduplicated by entry key. Membership is built here
 * from the validated index and the live personal set; entry snapshots go through the application's
 * unified resolver so a user-supplied supplement is what the card shows. All database work runs on
 * the application's single worker; the view is rebuilt on the main thread after each commit.
 *
 * [context] is the owning Activity: once it is destroyed, every queued worker and every posted
 * continuation checks [Activity.isDestroyed] and does nothing, so an old screen can never touch a
 * detached view. Database writes are not protected by those fast checks alone: [admission] is the
 * cross-thread linearization point, invalidated from [onDestroy] and admitted by the repository
 * immediately before each write transaction. A destroy that happens during a long read therefore
 * makes the later transaction refuse to start, while a transaction admitted first may finish
 * atomically (it is never interrupted halfway).
 */
class StudyScreen(
    private val app: ShiciApplication,
    private val context: Activity,
    private val onRerender: () -> Unit,
    private val onToast: (String) -> Unit
) {
    private val main = Handler(Looper.getMainLooper())
    private val zone = ZoneId.systemDefault()
    private var generation = 0
    // One in-flight commit/settings write at a time so a double tap cannot submit two feedbacks.
    private var busy = false
    // Shared by every write this surface starts. onDestroy invalidates it once; the repository checks
    // it right before BEGIN, never while holding a lock across a read or a transaction. The owner
    // liveness read is supplementary to the explicit cross-thread invalidation.
    private val admission = StudyAdmission { !context.isDestroyed }

    /** Invalidates every not-yet-admitted write of this surface. Called from Activity.onDestroy. */
    fun onDestroy() { admission.invalidate() }

    fun render(content: LinearLayout) {
        content.removeAllViews()
        content.addView(context.text("背词复习 · 统一背诵", 26f, Paper.ink, true))
        content.addView(context.text("固定间隔复习，非经过验证的记忆算法；只按你的真实反馈推进。", 16f, Paper.secondary))
        content.addView(context.text("到期复习优先；之后先学个人生词（最近查询优先），再按考研/六级原书顺序稳定交错。", 16f, Paper.secondary))
        content.space(16)
        content.addView(context.text("正在读取学习进度…", 16f, Paper.secondary))
        load(content)
    }

    private fun load(content: LinearLayout) {
        val token = ++generation
        app.worker.execute {
            // A queued load of a screen whose activity is already gone must not even deal a card.
            if (context.isDestroyed) return@execute
            val result = runCatching {
                // planFor loads the personal set and the pinned book index (long reads); studyView
                // admits the pending-card write only after that, so a destroy during the load still
                // persists nothing.
                app.repository.studyView(planFor(), System.currentTimeMillis(), zone, admission)
            }
            main.post {
                if (context.isDestroyed) return@post
                if (token != generation) return@post
                result.onSuccess { bind(content, it) }
                    .onFailure {
                        content.removeAllViews()
                        content.addView(context.text("背词暂时不可用：${it.message}", 18f, Paper.error))
                    }
            }
        }
    }

    /**
     * The one unified plan: personal vocabulary in recent-query order first, then the two fixed
     * source books interleaved in their original order, deduplicated by entry key. 个人生词 is a
     * runtime set, 考研/六级 are the pinned index; neither is turned into a separate queue here.
     * Learned keys are filtered by the repository (due states keep priority), and personal priority
     * never bypasses a due review. Snapshots resolve through the application's unified resolver so
     * the card shows the latest original-or-user-supplement meaning instead of a stale word row.
     */
    private fun planFor(): StudyMembership {
        val books = app.studyBooks.getOrThrow()
        val kaoyan = books.orderedMembers(StudyBook.KAOYAN) ?: error("学习词书缺少${StudyBook.KAOYAN.label}归属")
        val cet6 = books.orderedMembers(StudyBook.CET6) ?: error("学习词书缺少${StudyBook.CET6.label}归属")
        val keys = UnifiedStudyPool.merged(app.repository.personalMembers(), kaoyan, cet6)
        return StudyMembership(StudyBook.ALL, keys) { app.resolveEntry(it) }
    }

    private fun bind(content: LinearLayout, view: StudyView) {
        content.removeAllViews()
        content.addView(context.text("背词复习 · 统一背诵", 26f, Paper.ink, true))
        content.addView(context.text("固定间隔复习，非经过验证的记忆算法；只按你的真实反馈推进。", 16f, Paper.secondary))
        content.addView(context.text("统一范围：个人生词 + 考研 + 六级；同词只保留一份进度，不要求切换词书。", 16f, Paper.secondary))
        content.space(8)
        content.addView(goalRow(view.settings.dailyNewGoal))
        content.addView(context.text("范围 ${StudyRules.MIN_DAILY_NEW_GOAL}–${StudyRules.MAX_DAILY_NEW_GOAL}，默认 ${StudyRules.DEFAULT_DAILY_NEW_GOAL}。", 16f, Paper.secondary))
        content.addView(context.text("释义来自原始词书（未逐条核对）或本机用户补充；不联网、不自动生成。", 16f, Paper.secondary))
        content.divider()
        content.addView(progressBlock(view.progress))
        content.space(16)
        val card = view.card
        if (card == null) {
            content.addView(context.text(emptyMessage(view.progress), 18f, Paper.ink))
            return
        }
        content.addView(cardBlock(card, view.credential))
    }

    private fun goalRow(goal: Int): LinearLayout = context.row().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(context.text("每日新词目标", 18f), LinearLayout.LayoutParams(0, -2, 1f))
        addView(context.button("−5", primary = false) { setGoal(goal - 5) })
        addView(context.text("$goal", 22f, Paper.ink, true).apply {
            gravity = Gravity.CENTER; setPadding(context.dp(16), 0, context.dp(16), 0)
        })
        addView(context.button("+5", primary = false) { setGoal(goal + 5) })
    }

    private fun progressBlock(progress: StudyProgress): LinearLayout = context.column().apply {
        addView(context.text("今日新词 ${progress.introducedToday} 个 · 今日剩余新词额度 ${progress.newRemaining} 个", 18f))
        addView(context.text("到期待复习 ${progress.dueNow} 词 · 今日已反馈 ${progress.todayAnswers} 次（${progress.todayReviewed} 词）· 累计已学 ${progress.totalIntroduced} 词",
            16f, Paper.secondary))
        progress.nextDueAt?.let { addView(context.text("下一张到期：${time(it)}", 16f, Paper.secondary)) }
    }

    private fun cardBlock(card: StudyCard, credential: Long): LinearLayout = context.column().apply {
        setPadding(context.dp(20), context.dp(20), context.dp(20), context.dp(20))
        background = context.shape(Paper.surface, 12, true)
        addView(context.text(if (card.newWord) "新词 · 首次学习" else "复习 · 阶段 ${stageLabel(card.stageBefore)}", 16f, Paper.green))
        addView(context.text(card.entry.headword, 32f, Paper.ink, true))
        addView(context.text(card.entry.partOfSpeech.ifBlank { "—" }, 16f, Paper.secondary))
        if (!card.answerRevealed) {
            space(8)
            addView(context.button("显示答案", primary = true) { reveal(card.token, credential) })
            addView(context.text("先自己回忆词义，再看答案，然后选择真实反馈。", 16f, Paper.secondary))
        } else {
            divider()
            addView(context.text(card.entry.meaning, 20f, Paper.ink))
            // The snapshot's own provenance is shown with the answer so a historical source/version is
            // never implied to be the current one. A user supplement is labelled by its source instead
            // of being presented as original word-book content.
            val provenance = listOf(card.entry.source, card.entry.version).filter { it.isNotBlank() }
            addView(context.text(if (provenance.isEmpty()) "释义来源 未标注" else "释义来源 ${provenance.joinToString(" · ")}",
                16f, Paper.secondary))
            if (card.note.isNotBlank()) {
                space(8)
                addView(context.text("我的笔记", 16f, Paper.secondary))
                addView(context.text(card.note, 18f, Paper.ink))
            }
            space(8)
            addView(context.text("这次记得怎么样？", 18f, bold = true))
            val row = context.row()
            FeedbackKind.entries.forEach { kind ->
                val button = context.button(kind.label(), primary = kind == FeedbackKind.KNOW) {
                    submit(card.token, kind, credential)
                }
                row.addView(button, LinearLayout.LayoutParams(0, -2, 1f).apply { marginEnd = context.dp(8) })
            }
            addView(row)
            addView(context.text("${FeedbackKind.KNOW.label()}：进入下一阶段；${FeedbackKind.FUZZY.label()}：阶段不变、明天再来；${FeedbackKind.FORGET.label()}：回到第一阶段、约 10 分钟后再出现。", 16f, Paper.secondary))
        }
    }

    private fun emptyMessage(progress: StudyProgress): String = when {
        progress.dueNow > 0 -> "有 ${progress.dueNow} 个到期词，但暂时没有可显示的卡片。"
        progress.newRemaining > 0 -> "可以开始学习新词。"
        progress.nextDueAt != null -> "今日目标已完成，也没有到期复习；下一张 ${time(progress.nextDueAt!!)} 到期。"
        else -> "统一范围内已全部学过，且暂无到期复习。"
    }

    private fun setGoal(goal: Int) {
        if (busy) return
        busy = true
        app.worker.execute {
            if (context.isDestroyed) return@execute
            val result = runCatching {
                // The unified scope is the only study setting the screen still exposes; the goal write
                // keeps the current global goal semantics but always persists the unified scope.
                app.repository.updateStudySettings(StudyBook.ALL, goal, admission)
            }
            main.post {
                if (context.isDestroyed) return@post
                busy = false
                result.onFailure { onToast("目标设置失败：${it.message}") }
                onRerender()
            }
        }
    }

    private fun reveal(token: String, credential: Long) {
        if (busy) return
        busy = true
        app.worker.execute {
            if (context.isDestroyed) return@execute
            val result = runCatching { app.repository.revealStudyCard(token, credential, admission) }
            main.post {
                if (context.isDestroyed) return@post
                busy = false
                if (result.getOrNull() != true) onToast("这张卡已失效，正在刷新")
                onRerender()
            }
        }
    }

    private fun submit(token: String, kind: FeedbackKind, credential: Long) {
        if (busy) return
        busy = true
        app.worker.execute {
            if (context.isDestroyed) return@execute
            val result = runCatching {
                // Re-check the activity on the worker right before the write too. The repository then
                // performs its own admission check immediately before BEGIN, after every long read
                // (including the settings/personal-member reads), so a destroy that lands between
                // canCommit and BEGIN still writes nothing.
                app.repository.commitStudyFeedback(token, kind, System.currentTimeMillis(), zone,
                    credential, admission) {
                    !context.isDestroyed
                }
            }
            main.post {
                if (context.isDestroyed) return@post
                busy = false
                result.onSuccess { outcome ->
                    when (outcome) {
                        StudyCommitOutcome.COMMITTED, StudyCommitOutcome.IDEMPOTENT -> Unit
                        StudyCommitOutcome.CONFLICT -> onToast("这张卡已用其它反馈提交过，未重复记录")
                        StudyCommitOutcome.STALE -> onToast("学习数据已变化，这张卡失效，未记录")
                        StudyCommitOutcome.REJECTED -> onToast("这张卡已不是当前待答卡，未记录")
                    }
                }.onFailure { onToast("提交失败，未记录：${it.message}") }
                onRerender()
            }
        }
    }

    private fun stageLabel(stageBefore: Int): String =
        if (stageBefore < 0) "新词" else "第 ${stageBefore + 1} 阶段"

    private fun FeedbackKind.label(): String = when (this) {
        FeedbackKind.KNOW -> "认识"
        FeedbackKind.FUZZY -> "模糊"
        FeedbackKind.FORGET -> "忘记"
    }

    private fun time(at: Long): String =
        Instant.ofEpochMilli(at).atZone(zone).format(DateTimeFormatter.ofPattern("MM-dd HH:mm"))
}
