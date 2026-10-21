package com.shici.app.core.q1.host
// Explicit deterministic Android/MLKit/repository adapters. No Android or database runtime is claimed.
import com.shici.app.core.*
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs
import kotlin.math.hypot
annotation class SuppressLint(val value:String)
class Metrics {var density=1f;var scaledDensity=1f}
class Resources {val displayMetrics=Metrics()}
open class Context {val resources=Resources();open fun onDestroy(){}}
fun Context.dp(n:Int)=(n*resources.displayMetrics.density).toInt()
open class View(val context:Context){
 val resources get()=context.resources
 var width=480;var height=640;var measuredHeight=180;var originX=0;var originY=0
 var contentDescription="";var isClickable=false;var isFocusable=false;var isFocusableInTouchMode=false;var isEnabled=true
 var visibility=0;var background:Any?=null;var text="";var hint="";var action:(()->Unit)?=null
 var isAttachedToWindow=false;val isShown get()=visibility==VISIBLE
 open fun onAttachedToWindow(){};open fun onLayout(changed:Boolean,left:Int,top:Int,right:Int,bottom:Int){};open fun onWindowVisibilityChanged(v:Int){}
 val children=mutableListOf<View>();val viewTreeObserver=ViewTreeObserver()
 fun all():List<View> = listOf(this)+children.flatMap{it.all()}
 open fun onDraw(c:Canvas){};open fun onTouchEvent(e:MotionEvent)=true;open fun performClick():Boolean{action?.invoke();return true}
 fun invalidate(){};fun getLocationOnScreen(a:IntArray){a[0]=originX;a[1]=originY}
 fun setPadding(a:Int,b:Int,c:Int,d:Int){};fun addView(v:View){children+=v};fun measure(a:Int,b:Int){}
 fun setOnKeyListener(f:(View,Int,KeyEvent)->Boolean){};fun requestFocus()=true
 fun setTextColor(i:Int){};fun setOnClickListener(f:(View)->Unit){action={f(this)}};fun click(){if(isEnabled)action?.invoke()}
 fun post(f:()->Unit){f()};fun setText(s:CharSequence){text=s.toString()};fun setSingleLine(b:Boolean){}
 object MeasureSpec {const val EXACTLY=1;const val AT_MOST=2;fun makeMeasureSpec(n:Int,m:Int)=n}
 companion object {const val GONE=8;const val VISIBLE=0}
}
class ViewTreeObserver {fun interface OnGlobalLayoutListener{fun onGlobalLayout()};val layouts=mutableListOf<OnGlobalLayoutListener>();fun addOnGlobalLayoutListener(l:OnGlobalLayoutListener){layouts+=l};fun removeOnGlobalLayoutListener(l:OnGlobalLayoutListener){layouts.remove(l)};fun dispatchLayout(){layouts.toList().forEach{it.onGlobalLayout()}};var isAlive=true;interface OnDrawListener{fun onDraw()};fun addOnDrawListener(l:OnDrawListener){l.onDraw()};fun removeOnDrawListener(l:OnDrawListener){}}
open class TextView(c:Context):View(c)
class Button(c:Context):TextView(c)
class LinearLayout(c:Context):View(c)
class EditText(c:Context):TextView(c)
class ScrollView(c:Context):View(c){var isFillViewport=false}
fun Context.column()=LinearLayout(this)
fun Context.text(s:String,size:Float=16f,color:Int=0,bold:Boolean=false)=TextView(this).apply{text=s}
fun Context.button(s:String,primary:Boolean=false,f:()->Unit)=Button(this).apply{text=s;action=f}
fun Context.inputField(s:String)=EditText(this)
fun View.space(n:Int=12){}
fun Context.shape(c:Int,r:Int=0,border:Boolean=false):Any=c
object Paper {const val error=1;const val green=2;const val surface=3;const val secondary=4;const val warning=5}
object Color {const val WHITE=-1;fun argb(a:Int,r:Int,g:Int,b:Int)=(a shl 24) or (r shl 16) or (g shl 8) or b}
class Paint {constructor(i:Int);constructor(p:Paint)
 var color=0;var style=Style.STROKE;var strokeWidth=0f;var strokeCap=Cap.ROUND;var strokeJoin=Join.ROUND;var textSize=16f;var textAlign=Align.CENTER;var typeface:Any?=null
 enum class Style{STROKE};enum class Cap{ROUND};enum class Join{ROUND};enum class Align{CENTER}
 val fontMetrics=FontMetrics();class FontMetrics{val ascent=-8f;val descent=2f}
 fun ascent()=-8f;fun descent()=2f;fun measureText(s:String)=s.length*textSize
 companion object{const val ANTI_ALIAS_FLAG=1}
}
object Typeface {const val NORMAL=0;const val BOLD=1;fun create(s:String,n:Int)=s}
class Path {fun moveTo(x:Float,y:Float){};fun lineTo(x:Float,y:Float){}}
class RectF(val left:Float,val top:Float,val right:Float,val bottom:Float){fun contains(x:Float,y:Float)=x>=left&&x<right&&y>=top&&y<bottom;fun centerX()=(left+right)/2;fun centerY()=(top+bottom)/2}
class Canvas {var bitmapDraws=0;fun drawPath(p:Path,i:Paint){};fun drawRoundRect(r:RectF,x:Float,y:Float,p:Paint){};fun drawText(s:String,x:Float,y:Float,p:Paint){}}
class ViewConfiguration {val scaledTouchSlop=8;companion object{fun get(c:Context)=ViewConfiguration()}}
class MotionEvent(val actionMasked:Int,val x:Float,val y:Float,val ids:List<Int> = listOf(0),val history:List<Pair<Float,Float>> = emptyList()){
 val rawX get()=x;val rawY get()=y;val historySize get()=history.size
 fun getPointerId(i:Int)=ids[i];fun findPointerIndex(id:Int)=ids.indexOf(id)
 fun getX(i:Int)=x;fun getY(i:Int)=y;fun getHistoricalX(i:Int,h:Int)=history[h].first;fun getHistoricalY(i:Int,h:Int)=history[h].second
 companion object{const val ACTION_DOWN=0;const val ACTION_UP=1;const val ACTION_MOVE=2;const val ACTION_CANCEL=3;const val ACTION_POINTER_DOWN=5;const val ACTION_POINTER_UP=6}
}
object WindowManager {class LayoutParams(val width:Int,val height:Int,val type:Int,val flags:Int,val format:Int){var gravity=0;var x=0;var y=0;var softInputMode=0;companion object{const val MATCH_PARENT=-1;const val TYPE_APPLICATION_OVERLAY=2038;const val FLAG_NOT_FOCUSABLE=8;const val FLAG_LAYOUT_IN_SCREEN=256;const val FLAG_LAYOUT_NO_LIMITS=512;const val FLAG_NOT_TOUCH_MODAL=32;const val SOFT_INPUT_ADJUST_RESIZE=16}}}
class Windows {var beforeAdd:((View)->Unit)?=null;var readyCallbacks=0;fun dispatchReady(){readyCallbacks++;views.keys.toList().forEach{it.isAttachedToWindow=true;it.onAttachedToWindow();it.onLayout(true,0,0,it.width,it.height);it.viewTreeObserver.dispatchLayout()}};val views=mutableMapOf<View,WindowManager.LayoutParams>();var throwAdd=false
 fun addView(v:View,p:WindowManager.LayoutParams){beforeAdd?.invoke(v);if(throwAdd)throw IllegalStateException("add failed");views[v]=p}
 fun updateViewLayout(v:View,p:WindowManager.LayoutParams){views[v]=p};fun removeViewImmediate(v:View){views.remove(v);v.isAttachedToWindow=false}
}
object PixelFormat{const val TRANSLUCENT=-3;const val RGBA_8888=1}
object Gravity{const val TOP=1;const val START=2;const val CENTER=4}
class KeyEvent(val action:Int){companion object{const val KEYCODE_BACK=4;const val ACTION_UP=1}}
class Queue {var rejectPost=false;val tasks=java.util.ArrayDeque<()->Unit>();val delayed=mutableListOf<Runnable>()
 fun execute(f:()->Unit){tasks.add(f)};fun post(f:()->Unit):Boolean{if(rejectPost)return false;tasks.add(f);return true};fun postDelayed(r:Runnable,n:Long){delayed+=r};fun removeCallbacks(r:Runnable){delayed.remove(r)}
 fun one(){tasks.removeFirst().invoke()};fun all(){var n=0;while(tasks.isNotEmpty()){check(n++<1000);one()}}
}
object SystemClock{var now=1L;fun elapsedRealtime()=now}
class Choreographer{fun postFrameCallback(f:(Long)->Unit){f(System.nanoTime())};companion object{fun getInstance()=Choreographer()}}
object CaptureStatus{var generation=1L;var state=State();class State{var phase=CapturePhase.ACTIVE};fun update(p:CapturePhase,m:String){state.phase=p}}
enum class CapturePhase{ACTIVE,STOPPING,PREPARING,CLOSED,ERROR}
class Performance{val stages=mutableListOf<String>();fun click(){stages+="click"};fun selectionReady(){stages+="ready"};fun selectionSubmit(){stages+="submit"};fun stage(a:String,b:String){stages+="$a:$b"};fun end(a:String,b:String){stages+="$a:$b"};fun definitionVisible(){stages+="visible"}}
class Bitmap(val width:Int,val height:Int,val pixels:IntArray=IntArray(width*height){-1}){
 var recycled=false;var recycleCount=0;var throwCopy=false;var throwRead=false;var failReadAfter:Int?=null;var pixelReads=0;var beforePixelRead:((Int)->Unit)?=null
 fun getPixels(row:IntArray,o:Int,stride:Int,x:Int,y:Int,w:Int,h:Int){check(!recycled);pixelReads++;beforePixelRead?.invoke(pixelReads);if(throwRead || failReadAfter?.let{pixelReads>it}==true)throw IllegalStateException("pixel read failed");require(x>=0&&y>=0&&x+w<=width&&y+h<=height);pixels.copyInto(row,o,y*width+x,y*width+x+w)}
 fun recycle(){recycled=true;recycleCount++}
 fun copy(c:Config,b:Boolean):Bitmap?{if(throwCopy)return null;return Bitmap(width,height,pixels.copyOf())}
 enum class Config{ARGB_8888}
 companion object{fun createBitmap(b:Bitmap,x:Int,y:Int,w:Int,h:Int):Bitmap{check(!b.recycled);if(x==0&&y==0&&w==b.width&&h==b.height)return b;return Bitmap(w,h,IntArray(w*h){i->b.pixels[(y+i/w)*b.width+x+i%w]})}}
}
class InputImage(val b:Bitmap){companion object{fun fromBitmap(b:Bitmap,r:Int)=InputImage(b)}}
class OcrRect(val left:Int,val top:Int,val right:Int,val bottom:Int)
class Element(val text:String,val boundingBox:OcrRect?)
class Line(val boundingBox:OcrRect?,val elements:List<Element>)
class Block(val lines:List<Line>)
class OcrResult(val textBlocks:List<Block>)
class Task {var success:((OcrResult)->Unit)?=null;var failure:((Exception)->Unit)?=null;var complete:(()->Unit)?=null
 fun addOnSuccessListener(f:(OcrResult)->Unit):Task{success=f;return this};fun addOnFailureListener(f:(Exception)->Unit):Task{failure=f;return this};fun addOnCompleteListener(f:()->Unit):Task{complete=f;return this}
 fun succeed(r:OcrResult){success!!(r);complete!!()};fun fail(){failure!!(IllegalStateException("ocr failed"));complete!!()}
}
class Recognizer{val tasks=mutableListOf<Task>();val images=mutableListOf<Bitmap>();var closed=false;fun close(){closed=true};var throwing=false;fun process(i:InputImage):Task{if(throwing)throw IllegalStateException("process failed");images+=i.b;return Task().also{tasks+=it}}}
class Repository {val changes=LearningChanges();val deletions=DeletionGate();val events=linkedMapOf<String,QueryEvent>();var throwRecord=false;var beforeAdmission:(()->Unit)?=null
 fun record(e:QueryEvent,d:DictionaryEntry,replaces:String?,c:Long,admission:()->Boolean):Boolean{beforeAdmission?.invoke();if(!admission()||deletions.isStale(d.key,c))return false;if(throwRecord)throw IllegalStateException("disk full");if(replaces!=null)events.remove(replaces);events[e.id]=e;return true}
 fun revokeEvent(id:String,k:String,c:Long):RevokeOutcome{if(deletions.isStale(k,c)||events.remove(id)==null)return RevokeOutcome.MISSING;return RevokeOutcome.REVOKED}
}
enum class RevokeOutcome{REVOKED,MISSING}
class App{val repository=Repository();val worker=Queue();var dictionary:Result<RawKajwebDictionary> = Result.failure(IllegalStateException("not loaded"))}

class Image(val b:Bitmap,val timestamp:Long){var beforeWidthRead:(()->Unit)?=null;var beforePlaneRead:(()->Unit)?=null;val width get():Int{beforeWidthRead?.invoke();return b.width};val height get()=b.height;var planeMissing=false;var planeThrow=false;var bufferOverride:java.nio.ByteBuffer?=null;var rowStrideOverride:Int?=null;var pixelStrideOverride:Int?=null
 class Plane(val rowStride:Int,val pixelStride:Int,val buffer:java.nio.ByteBuffer)
 val planes:Array<Plane> get(){beforePlaneRead?.invoke();if(planeThrow||b.throwRead)throw IllegalStateException("synthetic plane read failed");if(planeMissing)return emptyArray();val buffer=bufferOverride?:java.nio.ByteBuffer.allocate(width*height*4).apply{b.pixels.forEach{put((it shr 16).toByte());put((it shr 8).toByte());put(it.toByte());put(255.toByte())};flip()};return arrayOf(Plane(rowStrideOverride?:width*4,pixelStrideOverride?:4,buffer))}
 var closed=false;fun close(){closed=true}}
class ImageReader(val width:Int,val height:Int){var pending:Image?=null;var listener:((ImageReader)->Unit)?=null
 var closed=false;var acquireFailure=false;fun close(){closed=true};fun acquireLatestImage():Image?{if(acquireFailure)throw IllegalStateException("synthetic acquire failed");return pending.also{pending=null}}
 fun emit(i:Image){pending=i;listener!!(this)}
 fun setOnImageAvailableListener(l:((ImageReader)->Unit)?,h:Queue?){listener=l}
 companion object{fun newInstance(w:Int,h:Int,f:Int,n:Int)=ImageReader(w,h)}
}

class ReleaseResource {var released=false;var stopped=false;fun release(){released=true};fun stop(){stopped=true}}
class ThreadResource{var quit=false;fun quitSafely(){quit=true}}

object BuildConfig {var DEBUG=true}
object Log {val entries=mutableListOf<String>();fun d(tag:String,s:String){entries+="$tag $s"}}

/** Debug-only logcat; no file, network, word, OCR text or image is emitted. */
class DebugPerformance {
    private var operation: OperationTiming? = null
    fun click() {
        if (!BuildConfig.DEBUG) return
        operation = OperationTiming(UUID.randomUUID().toString(), SystemClock::elapsedRealtime)
        emit("click", "pending")
    }
    fun selectionReady() {
        operation?.let { emit("selection_ready", "ok", "click_to_selection_ms=${it.clickToSelection()}") }
    }
    // The submit point is the real ACTION_UP of the single valid stroke; there is no Finish button.
    fun selectionSubmit() { operation?.release(); emit("selection_submit", "pending") }
    fun definitionVisible() {
        operation?.releaseToDefinition()?.let { emit("definition_visible", "ok", "submit_to_definition_ms=$it") }
    }
    fun stage(stage: String, status: String) { emit(stage, status) }
    fun end(status: String, stage: String) { emit(stage, status); operation = null }
    private fun emit(stage: String, status: String, additional: String = "") {
        if (!BuildConfig.DEBUG) return
        operation?.let {
            Log.d("ShiciPerf", "op=${it.id} stage=$stage status=$status elapsed_ms=${it.elapsed()} $additional".trim())
        }
    }
}

/** Native, service-context safe, with an explicit accessibility click path. */
class BubbleView(context: Context) : View(context) {
    var onDragStart: () -> Unit = {}
    var onDrag: (Float, Float) -> Unit = { _, _ -> }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 24f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.BOLD)
    }
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var dragged = false
    init {
        background = context.shape(Paper.green, 28)
        contentDescription = "拾词：点击后在原页面划词，拖动改变位置"
        isClickable = true; isFocusable = true
    }
    override fun onDraw(canvas: Canvas) {
        canvas.drawText("词", width / 2f, height / 2f - (labelPaint.ascent() + labelPaint.descent()) / 2f, labelPaint)
    }
    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.rawX; downY = event.rawY; dragged = false; onDragStart() }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - downX; val dy = event.rawY - downY
                if (abs(dx) + abs(dy) > touchSlop) dragged = true
                if (dragged) onDrag(dx, dy)
            }
            MotionEvent.ACTION_UP -> if (!dragged) performClick()
            MotionEvent.ACTION_CANCEL -> dragged = true
        }
        return true
    }
    override fun performClick(): Boolean { super.performClick(); return true }
}

@SuppressLint("ViewConstructor")
class LiveSelectionView(
    context: Context,
    private val screenWidth: Int,
    private val screenHeight: Int,
    private val onStroke: (HighlighterStroke) -> Unit,
    private val onTooShort: () -> Unit,
    private val onCancel: (String) -> Unit
) : View(context) {
    private val density = resources.displayMetrics.density
    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private val ink = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(96, 255, 213, 79)
        style = Paint.Style.STROKE
        strokeWidth = HighlighterTuning.BRUSH_WIDTH_DP * density
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
    }
    private val chipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.argb(214, 36, 47, 44) }
    private val chipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        textSize = 16f * resources.displayMetrics.scaledDensity
        textAlign = Paint.Align.CENTER
        typeface = Typeface.create("sans-serif", Typeface.NORMAL)
    }
    private val hintText = Paint(chipText).apply { textSize = 14f * resources.displayMetrics.scaledDensity }
    private var currentPoints: MutableList<HighlighterPoint>? = null
    private var currentPath: Path? = null
    private var pointerId = -1
    private var interrupted = false
    private var cancelPressed = false
    private var downX = 0f
    private var downY = 0f
    @Volatile private var armed = false
    private var windowReadyCallback: (() -> Unit)? = null
    private var windowReadyReported = false
    private var globalLayoutObserved = false
    private val globalLayoutListener = ViewTreeObserver.OnGlobalLayoutListener { reportWindowReady() }

    init {
        contentDescription = "透明划词层：沿完整单词一笔划过，抬笔即查；轻点不查询，可点取消退出"
        isClickable = true
        isFocusable = true
    }

    /** The clean entry frame is ready; ink feedback and one-stroke submission may start. */
    fun arm() {
        armed = true
        invalidate()
    }

    /**
     * Reports the first moment this overlay is genuinely usable: attached to a window, laid out
     * with a non-zero size, and visible. Merely calling addView is not accepted as readiness.
     * The callback runs once on the main thread; a removed view or a newer lookup therefore cannot
     * report late. No timing callback is used to delay or fake the gesture start.
     */
    fun awaitWindowReady(callback: () -> Unit) {
        if (windowReadyReported) {
            callback()
            return
        }
        windowReadyCallback = callback
        if (isAttachedToWindow && isShown && width > 0 && height > 0) {
            // A post runs after the current traversal in case layout already completed.
            post { reportWindowReady() }
        }
        observeLayout()
    }

    /** Cancels a pending readiness report when the layer is removed or its lookup is superseded. */
    fun cancelWindowReadiness() {
        windowReadyCallback = null
        runCatching { viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener) }
        globalLayoutObserved = false
    }

    private fun observeLayout() {
        if (globalLayoutObserved) return
        globalLayoutObserved = true
        runCatching { viewTreeObserver.addOnGlobalLayoutListener(globalLayoutListener) }
    }

    private fun reportWindowReady() {
        val callback = windowReadyCallback ?: return
        if (windowReadyReported || !isAttachedToWindow || !isShown || width <= 0 || height <= 0) return
        windowReadyReported = true
        windowReadyCallback = null
        runCatching { viewTreeObserver.removeOnGlobalLayoutListener(globalLayoutListener) }
        globalLayoutObserved = false
        callback()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        observeLayout()
        reportWindowReady()
    }

    override fun onLayout(changed: Boolean, left: Int, top: Int, right: Int, bottom: Int) {
        super.onLayout(changed, left, top, right, bottom)
        reportWindowReady()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) reportWindowReady()
    }

    private fun cancelRect(): RectF {
        val halfWidth = (chipText.measureText("取消取词") + 32f * density) / 2f
        val chipHeight = 44f * density
        val centerX = width / 2f
        val top = height - chipHeight - context.dp(32)
        return RectF(centerX - halfWidth, top, centerX + halfWidth, top + chipHeight)
    }

    private fun hintRect(): RectF {
        val text = "沿完整单词一笔划过 · 抬笔即查"
        val halfWidth = (hintText.measureText(text) + 28f * density) / 2f
        val height = 34f * density
        val centerX = width / 2f
        val top = context.dp(56).toFloat()
        return RectF(centerX - halfWidth, top, centerX + halfWidth, top + height)
    }

    override fun onDraw(canvas: Canvas) {
        if (!armed) return
        currentPath?.let { canvas.drawPath(it, ink) }
        drawChip(canvas, hintRect(), "沿完整单词一笔划过 · 抬笔即查", hintText, 34f * density)
        drawChip(canvas, cancelRect(), "取消取词", chipText, 44f * density)
    }

    private fun drawChip(canvas: Canvas, box: RectF, text: String, paint: Paint, height: Float) {
        val radius = height / 2f
        canvas.drawRoundRect(box, radius, radius, chipPaint)
        val metrics = paint.fontMetrics
        val baseline = box.centerY() - (metrics.ascent + metrics.descent) / 2f
        canvas.drawText(text, box.centerX(), baseline, paint)
    }

    private fun cancelCurrentStroke() {
        currentPoints = null
        currentPath = null
        pointerId = -1
    }

    private fun beginStroke(event: MotionEvent) {
        pointerId = event.getPointerId(0)
        currentPoints = mutableListOf(HighlighterPoint(event.x, event.y))
        currentPath = Path().apply { moveTo(event.x, event.y) }
    }

    private fun appendPoint(x: Float, y: Float) {
        val points = currentPoints ?: return
        val point = HighlighterPoint(x, y)
        if (points.lastOrNull() != point) {
            points += point
            currentPath?.lineTo(x, y)
        }
    }

    private fun appendEvent(event: MotionEvent) {
        val index = event.findPointerIndex(pointerId)
        if (index < 0) { cancelCurrentStroke(); return }
        if (event.actionMasked == MotionEvent.ACTION_MOVE) {
            for (history in 0 until event.historySize) {
                appendPoint(event.getHistoricalX(index, history), event.getHistoricalY(index, history))
            }
        }
        appendPoint(event.getX(index), event.getY(index))
    }

    /** View-local coordinates are translated to screen pixels; the overlay is display-aligned. */
    private fun toScreen(point: HighlighterPoint): HighlighterPoint {
        val location = IntArray(2)
        getLocationOnScreen(location)
        return HighlighterPoint(point.x + location[0], point.y + location[1])
    }

    private fun strokeLength(points: List<HighlighterPoint>): Float =
        points.zipWithNext().sumOf { (a, b) -> hypot((b.x - a.x).toDouble(), (b.y - a.y).toDouble()) }.toFloat()

    override fun onTouchEvent(event: MotionEvent): Boolean {
        // While the clean entry frame is still being acquired the layer only swallows touches: it
        // neither draws ink (which would pollute the frame) nor lets the tap reach the app below.
        if (!armed) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                interrupted = false
                cancelPressed = cancelRect().contains(event.x, event.y)
                downX = event.x
                downY = event.y
                if (!cancelPressed) beginStroke(event)
            }
            MotionEvent.ACTION_MOVE -> if (!cancelPressed && !interrupted) appendEvent(event)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP -> {
                // Any second pointer makes this attempt ambiguous: never bridge or submit it.
                interrupted = true
                cancelCurrentStroke()
            }
            MotionEvent.ACTION_UP -> {
                if (cancelPressed) {
                    cancelPressed = false
                    cancelCurrentStroke()
                    if (abs(event.x - downX) <= touchSlop && abs(event.y - downY) <= touchSlop &&
                        cancelRect().contains(event.x, event.y)) onCancel("已取消取词")
                    invalidate()
                    return true
                }
                if (interrupted) {
                    interrupted = false
                    cancelCurrentStroke()
                    invalidate()
                    onCancel("检测到多指操作，本次未查询")
                    return true
                }
                appendEvent(event)
                val points = currentPoints?.toList() ?: emptyList()
                cancelCurrentStroke()
                invalidate()
                if (points.size < 2 || strokeLength(points) < HighlighterTuning.MIN_STROKE_LENGTH_DP * density) {
                    onTooShort()
                    return true
                }
                onStroke(HighlighterStroke(points.map(::toScreen)))
            }
            MotionEvent.ACTION_CANCEL -> {
                cancelCurrentStroke()
                invalidate()
                onCancel("取词手势已中断，本次未查询")
            }
        }
        invalidate()
        return true
    }

    override fun performClick(): Boolean { super.performClick(); return true }
}

class CaptureHost : Context() {
    private enum class FramePurpose { ENTRY, VERIFY }
    companion object {const val ENTRY_FRAME_TIMEOUT_MS=2500L;const val VERIFY_FRAME_TIMEOUT_MS=1500L;const val LIVE_MODE_TIMEOUT_MS=30000L;const val PAGE_GUARD_TIMEOUT_MS=60000L}
    var display:ReleaseResource?=ReleaseResource();var projection:ReleaseResource?=ReleaseResource();val framesThread=ThreadResource();var pendingError:String?=null;var foregroundStopped=false
    fun stopForeground(i:Int){foregroundStopped=true}
    val STOP_FOREGROUND_REMOVE=1
    val frames=Queue();var reader:ImageReader?=null;val main=Queue();val gate=QueryGate();val perf=Performance();val windows=Windows()
    val app=App();var recognizer:Recognizer?=Recognizer(); @Volatile var stopped=false;var sessionGeneration=1L
    var screenWidth=480;var screenHeight=640;var bubbleX=20;var bubbleY=160;var bubble:View?=null;var panel:View?=null
    var deletionCredential=0L
    val requestedFrame=AtomicReference<FreshFrameRequest?>(null)
    @Volatile private var framePurpose=FramePurpose.ENTRY
    @Volatile private var lookupEpoch = 0L
    var frameTimeout:Runnable?=null;var live:LiveSelectionSession?=null;var liveView:LiveSelectionView?=null;var liveTimeout:Runnable?=null
    @Volatile private var pageGuard: LivePageGuard? = null
    private var pageGuardCounter = 0L
    private var pageGuardTimeout: Runnable? = null
    // Lower bound for page identity: the accepted lift image's own source timestamp, not the later
    // moment at which the guard object is created. A frame captured after the lift but delivered
    // before/while the guard is mounted must still be judged against the lift baseline.
    @Volatile private var pageGuardSourceNanos = 0L
    // Screen regions actually covered by this application's current card(s). Masks are applied per
    // pixel, so a card rectangle never hides the visible page pixels that merely share a tile or
    // lie inside a fixed outward margin.
    @Volatile private var pageOverlayBoxes: List<PixelBox> = emptyList()
    // Set only between the accepted lift frame and the pending card being mounted, so the card's
    // own frame is already excluded before observation starts. The pending identity is published
    // to the frame thread so a genuine update in this window is not lost.
    @Volatile private var pendingPageBaseline: LiveFrameFingerprint? = null
    @Volatile private var pendingPageBody: PixelBox? = null
    @Volatile private var pendingPageSourceNanos = 0L
    // A readable frame compared against the pending lift identity already disproved the page.
    @Volatile private var pendingPageChanged = false
    // The visible "已收录" card; invalidated if its word is deleted while the card is still on screen.
    private class DefinitionGuard(
        val key: String, val credential: Long, val page: LivePageGuard?, val manual: Boolean,
        val status: TextView, val undo: Button, val correct: Button, val retry: Button
    ) { @Volatile var invalid = false; @Volatile var saved = false }
    @Volatile private var definitionGuard: DefinitionGuard? = null
    private val pageLock = Any()
    var messages=mutableListOf<String>()
    fun toast(s:String){messages+=s}
    fun fail(s:String){messages+=s;stopped=true}
    fun frameBody()=PixelBox(0,0,screenWidth,screenHeight)
    fun start(){beginLiveSelection()}
    fun cancel(){cancelLiveMode("cancel")}
    fun close(){dismissPanel()}
    fun stop(){onDestroy()}
    fun showEntry(){showBubble()}
    fun tapEntry(){showBubble();bubble!!.performClick()}
    fun resizeTo(w:Int,h:Int){dismissPanel();screenWidth=w;screenHeight=h;showBubble()}
    fun generationChange(){CaptureStatus.generation++}
    fun imageBitmap(i:Image)=i.b
    fun queuePage(b:Bitmap):Image{if(reader==null)reader=newReader(screenWidth,screenHeight);val i=Image(b,System.nanoTime());reader!!.emit(i);return i}
    fun displayPage(b:Bitmap)=queuePage(b).also{main.all()}
    fun deliver(bitmap:Bitmap){displayPage(bitmap)}
    fun deliverLate(r:FreshFrameRequest,b:Bitmap){onVerifyFrame(r,b)}
    fun timeout(){frameTimeout!!.run()}
    fun nodes()=panel?.all()?:emptyList()
    fun click(s:String){nodes().first{it is Button && it.text==s}.click()}
    fun delete(k:String){app.repository.deletions.recordDeletion(k);app.repository.events.entries.removeIf{it.value.entryKey==k};app.repository.changes.publishCommitted()}
    fun restore(){app.repository.deletions.recordRestore();app.repository.events.clear();app.repository.changes.publishCommitted()}
    fun pump(){app.worker.all();main.all()}
    private val deletionListener: (Long) -> Unit = {
        val guard = definitionGuard
        if (guard != null && !guard.invalid && app.repository.deletions.isStale(guard.key, guard.credential)) {
            main.post { if (definitionGuard === guard) { invalidateDefinition(guard); endPageGuard() } }
        }
    }
    init {app.repository.changes.observe(deletionListener)}
    private fun observeAmbientPage(image: Image) {
        fun matchesImage(baseline: LiveFrameFingerprint, body: PixelBox): Boolean = try {
            val plane = image.planes.firstOrNull()
            plane != null && baseline.matchesPlane(image.width, image.height, body,
                plane.rowStride, plane.pixelStride, plane.buffer, pageOverlayExclusions())
        } catch (_: Exception) {
            false
        }

        fun timestampBelongsToGuard(candidate: LivePageGuard, timestampNanos: Long): Boolean =
            pageGuard === candidate &&
                (timestampNanos <= 0 || pageGuardSourceNanos <= 0 || timestampNanos >= pageGuardSourceNanos)

        fun invalidateOnFrameThread(guard: LivePageGuard) {
            val invalidated = synchronized(pageLock) {
                if (pageGuard !== guard || requestedFrame.get() != null) false
                else if (guard.invalidate()) {
                    // Close the gate on this frame thread before posting the UI update, so an
                    // OCR/dictionary/save worker racing on another thread cannot pass admission in
                    // the interval between observing the change and the main-thread card update.
                    gate.next()
                    true
                } else false
            }
            if (invalidated) {
                main.post { invalidatePage(guard, "页面已变化，本次查询已失效；已完成的提交不会自动撤销。") }
            }
        }

        val guard = pageGuard
        if (guard == null) {
            // The lift frame has been accepted but main delivery/guard mounting has not finished.
            // A frame captured at or after the accepted source timestamp is still page evidence;
            // compare it with the identity published at offer instead of dropping it in the
            // offer -> main-delivery -> guard-mount window.
            val pending = pendingPageBaseline ?: return
            val pendingBody = pendingPageBody ?: return
            if (stopped || !sessionIsActive() || liveView != null) return
            val awaiting = requestedFrame.get()
            // A request that has not accepted a frame yet is not covered by this pending identity;
            // an accepted request awaiting main delivery is, even though canInspect is now false.
            if (awaiting != null && !awaiting.isAwaitingDelivery()) return
            val sourceNanos = pendingPageSourceNanos
            if (sourceNanos > 0 && image.timestamp > 0 && image.timestamp < sourceNanos) return
            val same = matchesImage(pending, pendingBody)
            if (awaiting != null) {
                if (same) return
                // Invalidate the accepted-but-not-yet-delivered offer on this frame thread, so the
                // queued main callback can no longer adopt a stale lift frame. Nothing from it may
                // reach OCR/dictionary/record. The ownership check, gate advance, timeout detach and
                // pending-identity cleanup are one boundary with publishing a replacement request.
                val failedGeneration = sessionGeneration
                val failedEpoch = lookupEpoch
                if (closeFailedCaptureRequest(awaiting)) {
                    main.post { failLiveLookupIfCurrent(failedGeneration, failedEpoch,
                        "页面已变化，本次查询已取消。", "page_changed_gap") }
                    return
                }
                // Main delivery won the race; record the change for the pending guard below.
            }
            var raced: LivePageGuard? = null
            synchronized(pageLock) {
                val mounted = pageGuard
                if (mounted == null && !same && requestedFrame.get() == null) {
                    pendingPageChanged = true
                } else if (mounted != null && !same &&
                    timestampBelongsToGuard(mounted, image.timestamp) && mounted.invalidate()) {
                    // The guard was published while this frame was being compared. It is still a
                    // post-lift frame of this query, so close the gate before the worker can write.
                    gate.next()
                    raced = mounted
                }
            }
            val racedGuard = raced
            if (racedGuard != null) {
                main.post { invalidatePage(racedGuard, "页面已变化，本次查询已失效；已完成的提交不会自动撤销。") }
            }
            return
        }
        if (!guard.isValid || stopped || !sessionIsActive() || liveView != null ||
            requestedFrame.get() != null) return
        // Ignore a late image produced before the accepted lift frame. Zero timestamps keep the
        // old "no lower bound" safety semantics and are still checked, never silently trusted.
        if (!timestampBelongsToGuard(guard, image.timestamp)) return
        if (!matchesImage(guard.baseline, guard.body)) invalidateOnFrameThread(guard)
    }

    private fun pageOverlayExclusions(): List<PixelBox> {
        val bubbleBox = bubble?.let {
            PixelBox(bubbleX, bubbleY, bubbleX + dp(56), bubbleY + dp(56))
        }
        return if (bubbleBox == null) pageOverlayBoxes else pageOverlayBoxes + bubbleBox
    }

    private fun startPageGuard(baseline: LiveFrameFingerprint, body: PixelBox) {
        val previous = pageGuard
        pageGuard = null
        previous?.invalidate()
        pageGuardTimeout?.let { main.removeCallbacks(it) }
        // Keep the current card's exact exclusion: it was recorded by addPanel just before this call.
        val guard = LivePageGuard(++pageGuardCounter, body, baseline)
        var changedBeforeMount = false
        synchronized(pageLock) {
            // Publish the accepted lift image's own source timestamp as the page-identity lower
            // bound. The guard is then atomically visible to the frame thread; a frame that was
            // being compared against the pending identity either sets pendingPageChanged before
            // this lock or sees the mounted guard and invalidates it directly.
            pageGuardSourceNanos = pendingPageSourceNanos
            pageGuard = guard
            changedBeforeMount = pendingPageChanged
            pendingPageChanged = false
            pendingPageBaseline = null
            pendingPageBody = null
            pendingPageSourceNanos = 0L
        }
        val timeout = Runnable { expirePageGuard(guard) }
        pageGuardTimeout = timeout
        main.postDelayed(timeout, PAGE_GUARD_TIMEOUT_MS)
        if (changedBeforeMount) {
            // A post-lift frame delivered before the card was mounted already disproved identity.
            invalidatePage(guard, "页面已变化，本次查询已失效；已完成的提交不会自动撤销。")
        }
    }

    private fun endPageGuard() {
        synchronized(pageLock) {
            val previous = pageGuard
            pageGuard = null
            previous?.invalidate()
            pageGuardSourceNanos = 0L
            pendingPageBaseline = null
            pendingPageBody = null
            pendingPageSourceNanos = 0L
            pendingPageChanged = false
        }
        pageOverlayBoxes = emptyList()
        pageGuardTimeout?.let { main.removeCallbacks(it) }
        pageGuardTimeout = null
    }

    private fun invalidatePage(guard: LivePageGuard, message: String) {
        synchronized(pageLock) {
            if (pageGuard !== guard) return
            // The ambient observer may already have invalidated this guard on the frame thread; the
            // identity check above is what makes this main-thread UI update run at most once.
            guard.invalidate()
            pageGuard = null
            pageGuardSourceNanos = 0L
            pendingPageBaseline = null
            pendingPageBody = null
            pendingPageSourceNanos = 0L
            pendingPageChanged = false
        }
        pageOverlayBoxes = emptyList()
        pageGuardTimeout?.let { main.removeCallbacks(it) }
        pageGuardTimeout = null
        gate.next()
        perf.stage("page", "changed")
        val definition = definitionGuard
        if (definition == null) {
            removePanelView()
        } else if (!definition.invalid) {
            definition.invalid = true
            definition.status.text = "页面已变化，本次查询已失效；已完成的提交不会自动撤销"
            definition.status.setTextColor(Paper.error)
            definition.correct.isEnabled = false
            definition.retry.visibility = View.GONE
            // Undo remains available only for a commit this card already observed.
            definition.undo.isEnabled = definition.saved
        }
        toast(message)
        if (sessionIsActive() && bubble == null) showBubble()
    }

    private fun expirePageGuard(guard: LivePageGuard) {
        synchronized(pageLock) {
            if (pageGuard !== guard) return
            pageGuard = null
            pageGuardSourceNanos = 0L
            pendingPageBaseline = null
            pendingPageBody = null
            pendingPageSourceNanos = 0L
            pendingPageChanged = false
        }
        pageOverlayBoxes = emptyList()
        pageGuardTimeout = null
        guard.invalidate()
        gate.next()
        perf.stage("page", "guard_expired")
        val definition = definitionGuard
        if (definition == null) {
            removePanelView()
            if (sessionIsActive()) {
                toast("页面状态已无法确认，本次查询已取消。")
                showBubble()
            }
        } else if (!definition.invalid && !definition.saved) {
            definition.invalid = true
            definition.status.text = "未收录：页面状态已无法确认，本次查询已取消"
            definition.status.setTextColor(Paper.error)
            definition.undo.isEnabled = false
            definition.correct.isEnabled = false
            definition.retry.visibility = View.GONE
            toast("页面状态已无法确认，本次查询已取消。")
        }
    }

    private fun showBubble(): Boolean {
        if (stopped) return false
        if (bubble != null) return true
        val view = BubbleView(this)
        val params = WindowManager.LayoutParams(dp(56), dp(56), WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = bubbleX.coerceIn(0, (screenWidth - dp(56)).coerceAtLeast(0))
            y = bubbleY.coerceIn(0, (screenHeight - dp(56)).coerceAtLeast(0))
        }
        var originalX = 0; var originalY = 0
        view.onDragStart = { originalX = params.x; originalY = params.y }
        view.onDrag = { dx, dy ->
            params.x = (originalX + dx.toInt()).coerceIn(0, (screenWidth - dp(56)).coerceAtLeast(0))
            params.y = (originalY + dy.toInt()).coerceIn(0, (screenHeight - dp(56)).coerceAtLeast(0))
            bubbleX = params.x; bubbleY = params.y
            if (bubble === view) windows.updateViewLayout(view, params)
        }
        view.setOnClickListener { beginLiveSelection() }
        return try { windows.addView(view, params); bubble = view; true }
        catch (e: Exception) { fail("悬浮窗不可用：${e.message}"); false }
    }

    private fun removeBubble() { bubble?.let { runCatching { windows.removeViewImmediate(it) } }; bubble = null }

    override fun onDestroy() {
        perf.end("stopped", "session_stop")
        stopped = true
        app.repository.changes.remove(deletionListener)
        dismissPanel(); removeBubble()
        display?.release(); display = null
        projection?.stop(); projection = null
        reader?.setOnImageAvailableListener(null, null)
        val old = reader; reader = null
        val closingGeneration = CaptureStatus.generation
        if (CaptureStatus.state.phase != CapturePhase.PREPARING)
            CaptureStatus.update(CapturePhase.STOPPING, "正在关闭 · 释放捕获与浮层")
        frames.post {
            old?.close(); framesThread.quitSafely()
            main.post {
                if (CaptureStatus.generation == closingGeneration && CaptureStatus.state.phase == CapturePhase.STOPPING)
                    CaptureStatus.update(if (pendingError == null) CapturePhase.CLOSED else CapturePhase.ERROR,
                        pendingError ?: "已关闭 · 捕获与浮层已释放")
            }
        }
        recognizer?.close(); recognizer = null
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun newReader(width: Int, height: Int): ImageReader {
        require(width in 1..8192 && height in 1..8192 && width.toLong() * height <= 16_777_216L) { "捕获尺寸超出原型安全范围" }
        return ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2).apply {
        setOnImageAvailableListener({ source ->
            /**
             * The current source could not supply a trustworthy frame (a thrown acquisition or an
             * image whose geometry does not match the reader). That is not "no new image": the
             * active query's page authority can no longer be proven, so close it on this frame
             * thread before any worker can pass record admission, then notify the UI. A late frame
             * from a replaced reader or a different generation is ignored.
             */
            fun unusableSource(stage: String, message: String) {
                if (source !== reader || stopped || !sessionIsActive()) return
                val guard = pageGuard
                if (guard != null) {
                    val invalidated = synchronized(pageLock) {
                        // A published replacement request owns the gate now; an old guard must not
                        // advance it past that request's token. beginLiveSelection already closes
                        // any guard before publishing, under this same lock.
                        if (pageGuard !== guard || requestedFrame.get() != null) false
                        else if (guard.invalidate()) { gate.next(); true } else false
                    }
                    if (invalidated) main.post { invalidatePage(guard, message) }
                    return
                }
                val request = requestedFrame.get()
                if (request != null) {
                    // Stamp the failed attempt before its request is removed. The replacement cannot
                    // publish a new request before the ownership boundary in
                    // closeFailedCaptureRequest(), and if it already did, that boundary fails and no
                    // UI notification is posted at all.
                    val failedGeneration = sessionGeneration
                    val failedEpoch = lookupEpoch
                    if (closeFailedCaptureRequest(request)) {
                        main.post { failLiveLookupIfCurrent(failedGeneration, failedEpoch, message, stage) }
                    }
                    return
                }
                // The lift identity is published but the guard is not mounted yet: the pending
                // card mount will see this flag and invalidate before OCR/save can continue.
                synchronized(pageLock) {
                    if (pendingPageBaseline != null) pendingPageChanged = true
                }
            }
            // A plain null return means the source simply has no newer image and stays neutral.
            val image = try { source.acquireLatestImage() } catch (_: IllegalStateException) {
                unusableSource("frame_acquire", "无法读取当前页面画面；本次查询已取消。")
                null
            }
            if (image != null) {
                val request = requestedFrame.get()
                var bitmap: Bitmap? = null
                try {
                    val current = source === reader &&
                        image.width == source.width && image.height == source.height
                    if (source === reader && !current) {
                        // Wrong geometry from the current reader cannot prove the accepted page.
                        unusableSource("frame_geometry", "画面尺寸异常；本次查询已取消。")
                    } else if (current && request != null && frameIsCurrent(request) &&
                        request.canInspect(image.timestamp, SystemClock.elapsedRealtime())) {
                        val purpose = framePurpose
                        val captured = imageBitmap(image)
                        bitmap = captured
                        val black = FrameContent.isClearlyBlack(captured.width, captured.height, request.body) { x, y, row ->
                            captured.getPixels(row, 0, row.size, x, y, row.size, 1)
                        }
                        val firstBlack = black && !request.rejectedBlack
                        // Retain the accepted image's identity on this frame thread, before the
                        // main-thread hand-off. A newer frame that arrives while the request is
                        // awaiting delivery can then be compared instead of silently dropped.
                        val acceptedIdentity = if (black) null else runCatching {
                            LiveFrameFingerprint.sample(captured.width, captured.height, request.body) { x, y, row ->
                                captured.getPixels(row, 0, row.size, x, y, row.size, 1)
                            }
                        }.getOrNull()
                        if (!black && acceptedIdentity == null) {
                            // A readable candidate whose identity cannot be retained could later
                            // be adopted without a proven page identity: reject it up front.
                            val failedGeneration = sessionGeneration
                            val failedEpoch = lookupEpoch
                            if (closeFailedCaptureRequest(request)) {
                                main.post {
                                    failLiveLookupIfCurrent(failedGeneration, failedEpoch,
                                        if (purpose == FramePurpose.VERIFY)
                                            "无法读取抬笔后的页面画面；本次未查询。请返回做题后重试。"
                                        else "无法读取当前页面画面；本次未查询。请返回做题后重试。",
                                        if (purpose == FramePurpose.VERIFY) "verify_frame" else "entry_frame")
                                }
                            }
                        } else if (request.offer(image.timestamp, SystemClock.elapsedRealtime(), black)) {
                            if (acceptedIdentity != null) {
                                synchronized(pageLock) {
                                    pendingPageBaseline = acceptedIdentity
                                    pendingPageBody = request.body
                                    pendingPageSourceNanos = image.timestamp
                                    pendingPageChanged = false
                                }
                            }
                            if (main.post {
                                if (source === reader && frameIsCurrent(request) &&
                                    request.complete(SystemClock.elapsedRealtime()) && requestedFrame.compareAndSet(request, null)) {
                                    clearFrameTimeout()
                                    when (purpose) {
                                        FramePurpose.ENTRY -> onEntryFrame(request, captured)
                                        FramePurpose.VERIFY -> onVerifyFrame(request, captured)
                                    }
                                } else {
                                    captured.recycle()
                                    if (frameIsCurrent(request)) expireFrame(request, purpose)
                                }
                            }) bitmap = null // The main-thread callback now owns this bitmap.
                        } else if (firstBlack && request.rejectedBlack) {
                            main.post { if (frameIsCurrent(request)) perf.stage("frame_rejected", "black") }
                        }
                    } else if (current) {
                        // This image was not taken as the one-shot entry/lift frame. It is still
                        // page evidence while an accepted frame awaits main delivery or while a
                        // pending/guard identity is published: observe it, never drop it here.
                        observeAmbientPage(image)
                    }
                } catch (e: Exception) {
                    main.post {
                        if (request != null && frameIsCurrent(request)) {
                            perf.end("failed", "frame_conversion")
                            dismissPanel()
                            showMessage("取词失败：${e.message}")
                        }
                    }
                } finally { bitmap?.recycle(); image.close() }
            }
        }, frames)
        }
    }

    private fun frameIsCurrent(request: FreshFrameRequest): Boolean =
        sessionIsActive() && requestedFrame.get() === request && gate.accepts(request.token)

    private fun closeFailedCaptureRequest(request: FreshFrameRequest): Boolean {
        var timeout: Runnable? = null
        val closed = synchronized(pageLock) {
            if (requestedFrame.get() !== request) return@synchronized false
            if (!requestedFrame.compareAndSet(request, null)) return@synchronized false
            // Close the old token before any new request can be published under the same lock.
            gate.next()
            // The timeout and pending identity here belong to the request just removed: a new
            // request cannot publish or publish a pending identity until this lock is released.
            timeout = frameTimeout
            frameTimeout = null
            pendingPageBaseline = null
            pendingPageBody = null
            pendingPageSourceNanos = 0L
            pendingPageChanged = false
            true
        }
        if (!closed) return false
        request.cancel()
        timeout?.let { main.removeCallbacks(it) }
        return true
    }

    private fun clearFrameTimeout() {
        frameTimeout?.let { main.removeCallbacks(it) }
        frameTimeout = null
    }

    private fun expireFrame(request: FreshFrameRequest, purpose: FramePurpose) {
        if (!frameIsCurrent(request) || !requestedFrame.compareAndSet(request, null)) return
        request.cancel(); gate.next(); clearFrameTimeout()
        // A timeout ends the one-shot request; any identity sampled at offer is no longer evidence.
        synchronized(pageLock) {
            pendingPageBaseline = null
            pendingPageBody = null
            pendingPageSourceNanos = 0L
            pendingPageChanged = false
        }
        val session = live
        removeLiveLayer()
        live = null
        session?.selection = null
        when (purpose) {
            FramePurpose.ENTRY -> {
                perf.end("failed", if (request.rejectedBlack) "frame_black_timeout" else "frame_timeout")
                showMessage(if (request.rejectedBlack)
                    "已丢弃主体黑帧，等待有效新画面超时。页面可能尚未就绪或禁止截图；不会复用旧截图或绕过保护。请返回做题后重试。"
                    else "未收到新的有效屏幕画面。安全页面可能禁止截图；不会复用旧截图。请返回做题后重试。")
            }
            FramePurpose.VERIFY -> {
                perf.end("failed", if (request.rejectedBlack) "verify_black_timeout" else "verify_timeout")
                showMessage(if (request.rejectedBlack)
                    "抬笔后只收到主体黑帧，本次未查询，也没有写入生词本。请返回做题后重试。"
                    else "抬笔后未收到新的有效画面，本次未查询，也没有写入生词本。请返回做题后重试。")
            }
        }
    }

    private fun armFrame(purpose: FramePurpose, body: PixelBox, deadlineMillis: Long): FreshFrameRequest {
        val request = synchronized(pageLock) {
            val published = FreshFrameRequest(gate.next(), deadlineMillis, body)
            framePurpose = purpose
            requestedFrame.set(published)
            // Advance only after the replacement is visible: a capture-thread failure that already
            // stamped the old epoch now sees the new request and cannot remove it.
            lookupEpoch++
            val timeout = Runnable { expireFrame(published, purpose) }
            frameTimeout = timeout
            main.postDelayed(timeout, (deadlineMillis - SystemClock.elapsedRealtime()).coerceAtLeast(0))
            published
        }
        Choreographer.getInstance().postFrameCallback {
            if (frameIsCurrent(request)) request.arm(System.nanoTime())
        }
        return request
    }

    private fun beginLiveSelection() {
        if (stopped || !sessionIsActive() || requestedFrame.get() != null || live != null || liveView != null) return
        synchronized(pageLock) {
            dismissPanel()
            if (!sessionIsActive()) return
            perf.click()
            deletionCredential = app.repository.deletions.capture()
            val body = frameBody()
            removeBubble()
            armFrame(FramePurpose.ENTRY, body, SystemClock.elapsedRealtime() + ENTRY_FRAME_TIMEOUT_MS)
        }
    }

    private fun onEntryFrame(request: FreshFrameRequest, bitmap: Bitmap) {
        // Consume the identity sampled when this entry image was accepted. It is the same image,
        // and must not linger as page evidence for the later lift request.
        val published = synchronized(pageLock) {
            val identity = pendingPageBaseline
            pendingPageBaseline = null
            pendingPageBody = null
            pendingPageSourceNanos = 0L
            pendingPageChanged = false
            identity
        }
        if (!sessionIsActive() || !gate.accepts(request.token)) { bitmap.recycle(); return }
        val fingerprint = published ?: runCatching {
            LiveFrameFingerprint.sample(bitmap.width, bitmap.height, request.body) { x, y, row ->
                bitmap.getPixels(row, 0, row.size, x, y, row.size, 1)
            }
        }.getOrNull()
        if (fingerprint == null) {
            bitmap.recycle()
            failLiveLookup("无法读取当前页面画面；本次未查询。请返回做题后重试。", "entry_frame")
            return
        }
        bitmap.recycle() // Only the compact fingerprint survives; the clean entry bitmap is released.
        val session = LiveSelectionSession(
            generation = CaptureStatus.generation,
            entryToken = request.token,
            deletionCredential = deletionCredential,
            screenWidth = screenWidth,
            screenHeight = screenHeight,
            body = request.body,
            entryFingerprint = fingerprint
        )
        live = session
        val view = LiveSelectionView(this, screenWidth, screenHeight,
            { stroke -> commitLiveStroke(session, stroke) },
            { toast("请沿完整单词一笔划过；轻点不查询") },
            { reason -> cancelLiveMode(reason) })
        if (!addLiveLayer(view)) return
        liveView = view
        scheduleLiveTimeout()
        // "可划" is reported only after the window is attached, laid out with a real size and
        // visible. addView alone merely registers the window. The checks reject a late callback
        // from a cancelled, superseded or stopped lookup.
        view.awaitWindowReady {
            if (liveView === view && live === session && sessionIsActive() &&
                session.generation == CaptureStatus.generation && gate.accepts(session.entryToken)) {
                view.arm()
                perf.selectionReady()
            }
        }
    }

    private fun commitLiveStroke(session: LiveSelectionSession, stroke: HighlighterStroke) {
        if (live !== session || liveView == null || !sessionIsActive() ||
            session.generation != CaptureStatus.generation || !gate.accepts(session.entryToken)) {
            cancelLiveMode(null)
            return
        }
        val selection = HighlighterGeometry.selection(listOf(stroke), session.screenWidth, session.screenHeight,
            session.screenWidth, session.screenHeight, resources.displayMetrics.density)
        if (selection == null) {
            toast("未形成有效划线；没有查询，也没有新增记录")
            return
        }
        removeLiveLayer()
        session.selection = selection
        perf.selectionSubmit()
        val request = armFrame(FramePurpose.VERIFY, session.body,
            SystemClock.elapsedRealtime() + VERIFY_FRAME_TIMEOUT_MS)
        session.verifyToken = request.token
    }

    private fun onVerifyFrame(request: FreshFrameRequest, bitmap: Bitmap) {
        val session = live
        val selection = session?.selection
        if (session == null || selection == null || !sessionIsActive() ||
            session.generation != CaptureStatus.generation || session.verifyToken != request.token ||
            !gate.accepts(request.token)) {
            bitmap.recycle()
            return
        }
        val published = synchronized(pageLock) { pendingPageBaseline }
        val fingerprint = published ?: runCatching {
            LiveFrameFingerprint.sample(bitmap.width, bitmap.height, session.body) { x, y, row ->
                bitmap.getPixels(row, 0, row.size, x, y, row.size, 1)
            }
        }.getOrNull()
        if (fingerprint == null) {
            bitmap.recycle()
            failLiveLookup("无法读取抬笔后的页面画面；本次未查询。请返回做题后重试。", "verify_frame")
            return
        }
        if (!session.entryFingerprint.matches(fingerprint)) {
            bitmap.recycle()
            failLiveLookup("划选期间页面已变化，本次未查询。请在页面稳定后重新划选。", "page_changed")
            return
        }
        // Fresh lift frame, clean of bubble/ink/card, and materially the same page as the entry
        // frame: this is the only frame handed to OCR. Its colour identity becomes the page
        // baseline for the following OCR -> dictionary -> display/save/retry admissions, with the
        // image's own source timestamp as the lower bound for later observations.
        live = null
        session.selection = null
        // A differing frame observed between the accepted offer and this delivery is retained:
        // startPageGuard consumes it after mounting and invalidates before OCR/save can continue.
        synchronized(pageLock) {
            pendingPageSourceNanos = request.acceptedTimestampNanos
            pendingPageBody = session.body
            // Publish the identity last: a frame thread that sees it also sees source and body.
            pendingPageBaseline = fingerprint
        }
        perf.stage("frame", "verified")
        recognize(bitmap, selection, session.deletionCredential)
    }

    private fun failLiveLookupIfCurrent(generation: Long, epoch: Long, message: String, stage: String) {
        if (stopped || generation != sessionGeneration || CaptureStatus.generation != generation) return
        if (epoch != lookupEpoch || !sessionIsActive()) return
        failLiveLookup(message, stage)
    }

    private fun failLiveLookup(message: String, stage: String) {
        removeLiveLayer()
        live = null
        endPageGuard()
        gate.next()
        perf.end("failed", stage)
        showMessage(message)
    }

    private fun addLiveLayer(view: LiveSelectionView): Boolean = try {
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0; y = 0
        }
        windows.addView(view, params)
        true
    } catch (e: Exception) {
        failLiveLookup("无法打开透明划词层：${e.message}；本次未查询。", "live_layer")
        false
    }

    private fun removeLiveLayer() {
        liveTimeout?.let { main.removeCallbacks(it) }
        liveTimeout = null
        val view = liveView
        liveView = null
        view?.cancelWindowReadiness()
        view?.let { runCatching { windows.removeViewImmediate(it) } }
    }

    private fun scheduleLiveTimeout() {
        liveTimeout?.let { main.removeCallbacks(it) }
        val session = live ?: return
        val timeout = Runnable {
            if (live === session && liveView != null) cancelLiveMode("取词已超时，本次未查询")
        }
        liveTimeout = timeout
        main.postDelayed(timeout, LIVE_MODE_TIMEOUT_MS)
    }

    private fun cancelLiveMode(reason: String?) {
        val hadLive = live != null || liveView != null
        removeLiveLayer()
        live?.selection = null
        live = null
        endPageGuard()
        gate.next()
        requestedFrame.getAndSet(null)?.cancel()
        clearFrameTimeout()
        // A later notification from the cancelled attempt must not reopen UI for it.
        lookupEpoch++
        if (reason != null) toast(reason)
        if (hadLive) perf.end("cancelled", "live_cancel")
        if (sessionIsActive() && bubble == null) showBubble()
    }

    private fun addPanel(view: View) {
        removePanelView()
        val width = minOf(dp(440), (screenWidth - dp(24)).coerceAtLeast(dp(200)))
        // A long definition scrolls inside a card that never grows into a full-screen surface.
        val maxHeight = (screenHeight * 3 / 4).coerceAtLeast(dp(240))
        view.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(maxHeight, View.MeasureSpec.AT_MOST))
        val height = view.measuredHeight.coerceIn(1, maxHeight)
        // Exclude only the exact centred window this card actually covers. There is deliberately
        // no fixed outward margin and no accumulated history: a word just above the card, or page
        // pixels left visible around it, must remain page evidence.
        val left = (screenWidth - width) / 2
        val top = (screenHeight - height) / 2
        pageOverlayBoxes = listOf(PixelBox(left, top,
            (left + width).coerceAtMost(screenWidth), (top + height).coerceAtMost(screenHeight)))
        val params = WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT).apply {
            gravity = Gravity.CENTER
            softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
        }
        view.isFocusableInTouchMode = true
        view.setOnKeyListener { _, key, event ->
            if (key == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) { dismissPanel(); showBubble(); true } else false
        }
        try { windows.addView(view, params); panel = view; view.requestFocus() }
        catch (e: Exception) { fail("无法打开取词界面：${e.message}") }
    }

    private fun removePanelView() { definitionGuard = null; panel?.let { runCatching { windows.removeViewImmediate(it) } }; panel = null }

    private fun invalidateDefinition(guard: DefinitionGuard) {
        guard.invalid = true
        guard.status.text = "未收录：该词已删除或查询已失效"; guard.status.setTextColor(Paper.error)
        guard.undo.isEnabled = false; guard.correct.isEnabled = false; guard.retry.visibility = View.GONE
    }
    // A floating card is out of date once it is invalidated or its word was deleted after the query
    // credential was captured; both must win over any late success/failure result.

    private fun definitionCardStale(guard: DefinitionGuard): Boolean =
        guard.invalid || app.repository.deletions.isStale(guard.key, guard.credential)

    private fun dismissPanel() {
        perf.end("closed", "dismiss")
        gate.next(); requestedFrame.getAndSet(null)?.cancel(); clearFrameTimeout()
        // The dismissed attempt is over; queued failure notifications for it must stay silent.
        lookupEpoch++
        removeLiveLayer()
        live?.selection = null
        live = null
        endPageGuard()
        removePanelView()
    }

    private fun recognize(bitmap: Bitmap, selection: HighlighterSelection, credential: Long) {
        val area = selection.crop
        val crop = try {
            val slice = Bitmap.createBitmap(bitmap, area.left, area.top, area.width, area.height)
            // OCR owns its bitmap until completion; never share the live frame after this point.
            if (slice === bitmap) checkNotNull(bitmap.copy(Bitmap.Config.ARGB_8888, false)) else slice
        } catch (e: Exception) {
            bitmap.recycle()
            perf.stage("ocr", "failed"); showMessage("无法准备识别画面：${e.message}"); return
        }
        bitmap.recycle() // The only retained pixels are the necessary stroke-area crop.
        val token = gate.next()
        removePanelView()
        val pending = column().apply { setPadding(dp(24), dp(24), dp(24), dp(24)); background = shape(Paper.surface) }
        pending.addView(text("正在本地识别笔迹附近的文字…", 20f, bold = true))
        pending.addView(button("重新划选") { beginLiveSelection() }); pending.space(8)
        pending.addView(button("取消") { dismissPanel(); showBubble() })
        addPanel(pending)
        if (!sessionIsActive() || !gate.accepts(token)) {
            pendingPageBaseline = null
            pendingPageBody = null
            pendingPageSourceNanos = 0L
            pendingPageChanged = false
            crop.recycle()
            return
        }
        // The pending card is now mounted and its exact exclusion recorded; observation can begin.
        // The pending identity is kept published until startPageGuard consumes it, so a frame that
        // arrives while the card is being mounted is still compared rather than lost.
        val baseline = pendingPageBaseline
        val body = pendingPageBody
        if (baseline != null && body != null) startPageGuard(baseline, body)
        if (!sessionIsActive() || !gate.accepts(token)) {
            // A post-lift frame already disproved identity while the card was mounted.
            crop.recycle()
            return
        }
        val task = try {
            checkNotNull(recognizer).process(InputImage.fromBitmap(crop, 0))
        } catch (e: Exception) {
            crop.recycle()
            if (sessionIsActive() && gate.accepts(token)) {
                perf.stage("ocr", "failed"); showMessage("识别失败：${e.message}")
            }
            return
        }
        task.addOnSuccessListener { result ->
            if (sessionIsActive() && gate.accepts(token)) {
                var lineIndex = 0
                val elements = buildList {
                    result.textBlocks.forEach { block -> block.lines.forEach { line ->
                        val index = lineIndex++
                        val lineRect = line.boundingBox
                        if (lineRect != null) {
                            val lineBox = HighlighterGeometry.restoreBox(FloatBox(lineRect.left.toFloat(), lineRect.top.toFloat(),
                                lineRect.right.toFloat(), lineRect.bottom.toFloat()), area)
                            line.elements.forEach { element ->
                                element.boundingBox?.let { box ->
                                    add(HighlighterWord(element.text, HighlighterGeometry.restoreBox(FloatBox(box.left.toFloat(),
                                        box.top.toFloat(), box.right.toFloat(), box.bottom.toFloat()), area), index, lineBox))
                                }
                            }
                        }
                    } }
                }
                val selected = HighlighterGeometry.hitWords(selection, elements)
                val raw = HighlighterGeometry.selectedText(selected)
                when {
                    selected.isEmpty() -> {
                        perf.stage("ocr_empty", "failed")
                        showMessage("笔迹没有命中可识别文字。没有查询或新增记录；请沿字形重新划选，或手动纠错。")
                    }
                    selected.size == 1 && RawKajwebDictionary.normalize(selected.first().text) != null ->
                        lookup(selected.first().text, raw, credentialOverride = credential)
                    else -> {
                        perf.stage("ocr_multiple", "needs_confirmation")
                        showSelectedText(raw, selected.map { it.text })
                    }
                }
            }
        }.addOnFailureListener { e ->
            if (sessionIsActive() && gate.accepts(token)) { perf.stage("ocr", "failed"); showMessage("识别失败：${e.message}") }
        }.addOnCompleteListener { crop.recycle() }
    }

    private fun showSelectedText(raw: String, words: List<String>, manual: Boolean = false) {
        gate.next()
        val content = card("选中原文")
        content.addView(text(raw, 18f)); content.space()
        content.addView(text("译句未接入 · 请选择一个词查词；不会批量自动加入生词本。", 16f, Paper.warning)); content.space()
        words.filter { RawKajwebDictionary.normalize(it) != null }.distinct().forEach { word ->
            content.addView(button("查词：$word") { lookup(word, raw, manual = manual) }); content.space(8)
        }
        content.addView(button("纠正选中文字") { showTextCorrection(raw) }); content.space(8)
        content.addView(button("重新划选") { beginLiveSelection() }); content.space(8)
        content.addView(button("取消") { dismissPanel(); showBubble() })
        addPanel(scroll(content))
    }

    private fun showTextCorrection(raw: String) {
        // The user is explicitly correcting the previous OCR text; no automatic OCR/page evidence
        // is being admitted from here, so stop observing the old page.
        endPageGuard()
        gate.next()
        val content = card("纠正选中文字")
        content.addView(text("保留大小写、标点与换行；确认后仍需选择一个词查词。译句未接入。"))
        val input = inputField("选中的原文").apply { setText(raw) }
        content.addView(input)
        content.addView(button("确认文字", true) {
            val corrected = input.text.toString()
            if (corrected.isBlank()) toast("选中文字不能为空；不会查询或入库")
            else showSelectedText(corrected, corrected.split(Regex("\\s+")), manual = true)
        }); content.space(8)
        content.addView(button("重新划选") { beginLiveSelection() }); content.space(8)
        content.addView(button("取消") { dismissPanel(); showBubble() })
        addPanel(scroll(content))
    }

    private fun lookup(form: String, raw: String, replaces: String? = null,
                       credentialOverride: Long? = null, manual: Boolean = false) {
        val token = gate.next()
        val credential = credentialOverride ?: deletionCredential
        app.worker.execute {
            if (!sessionIsActive() || !gate.accepts(token)) return@execute
            val lookup = runCatching { app.dictionary.getOrThrow().lookup(form) }
            main.post {
                if (!sessionIsActive() || !gate.accepts(token)) return@post
                lookup.onSuccess { result ->
                    when (result) {
                        LookupResult.Invalid -> { perf.stage("dictionary", "invalid"); showMessage("请输入一个有效词头，不自动拼接多个词。", raw, replaces) }
                        is LookupResult.Missing -> { perf.stage("dictionary", "missing"); showMessage("「${result.form}」未收录于原始词库。没有生成释义，也没有新增记录。", raw, replaces) }
                        is LookupResult.Match -> showDefinition(result.form, raw, result.entries.single(), replaces, credential, manual)
                    }
                }.onFailure {
                    perf.stage("dictionary", "failed")
                    showMessage("原始词库不可用：${it.message}。没有生成释义，也没有新增记录。", raw, replaces)
                }
            }
        }
    }

    private fun showMessage(message: String, raw: String = "", replaces: String? = null) {
        // A terminal message has no save/retry admission left; stop observing its page.
        endPageGuard()
        gate.next()
        val content = card("取词提示")
        content.addView(text(message, 16f, Paper.warning)); content.space()
        if (raw.isNotBlank()) {
            content.addView(text("选中原文：\n$raw")); content.space()
            content.addView(button("手动纠错") { showCorrection(raw, replaces) }); content.space(8)
        }
        content.addView(button("重新划选") { beginLiveSelection() }); content.space(8)
        content.addView(button("返回做题") { dismissPanel(); showBubble() })
        addPanel(scroll(content))
    }

    private fun showCorrection(raw: String, replaces: String?, originalRaw: String = raw) {
        // A correction is an explicit user action, not a late automatic result from the old page.
        endPageGuard()
        gate.next()
        val content = card("确认正确词形")
        content.addView(text("只查询本地原始未逐条核对词库；确认成功后原事件撤销，不双计。"))
        val input = EditText(this).apply { setText(raw); hint = "一个英文单词"; setSingleLine(true) }
        content.addView(input)
        content.addView(button("确认查询", true) { lookup(input.text.toString(), originalRaw, replaces, manual = true) }); content.space(8)
        content.addView(button("重新划选") { beginLiveSelection() }); content.space(8)
        content.addView(button("取消") { dismissPanel(); showBubble() })
        addPanel(scroll(content))
    }

    private fun showDefinition(form: String, raw: String, entry: DictionaryEntry, replaces: String?,
                               credential: Long, manual: Boolean = false) {
        val token = gate.next()
        val page = pageGuard
        val event = QueryEvent(UUID.randomUUID().toString(), entry.key, raw, form, System.currentTimeMillis())
        val content = card(entry.headword)
        content.addView(text("原词形：$form · ${entry.partOfSpeech}"))
        content.addView(text("选中原文：\n$raw"))
        content.addView(text(entry.meaning, 18f)); content.space()
        content.addView(text(RawKajwebDictionary.provenance(entry.source, entry.version), 14f, Paper.warning))
        val status = text("保存中…", 16f, Paper.secondary)
        content.addView(status)
        val undo = button("撤销本次收录") {}
        val correct = button("纠错") {}
        val retry = button("重试保存") {}
        undo.isEnabled = false; correct.isEnabled = false; retry.visibility = View.GONE
        content.addView(undo); content.space(8); content.addView(correct); content.space(8); content.addView(retry); content.space(8)
        content.addView(button("重新划选") { beginLiveSelection() }); content.space(8)
        content.addView(button("返回做题", true) { dismissPanel(); showBubble() })
        addPanel(scroll(content))
        val guard = DefinitionGuard(entry.key, credential, page, manual, status, undo, correct, retry)
        definitionGuard = guard
        correct.setOnClickListener {
            // Reject an expired card at the entry: an old card must not start a new correction flow.
            if (definitionCardStale(guard)) { invalidateDefinition(guard); return@setOnClickListener }
            showCorrection(form, event.id, raw)
        }
        val drawListener = object : ViewTreeObserver.OnDrawListener {
            var reported = false
            override fun onDraw() {
                if (!reported && sessionIsActive() && gate.accepts(token)) {
                    reported = true; perf.definitionVisible()
                    content.post { if (content.viewTreeObserver.isAlive) content.viewTreeObserver.removeOnDrawListener(this) }
                }
            }
        }
        content.viewTreeObserver.addOnDrawListener(drawListener)
        fun save() {
            // A stale card (word deleted after this query started) must not start/retry a save.
            if (definitionCardStale(guard)) { invalidateDefinition(guard); return }
            // Retry/initial save from an automatic OCR lookup may only run while the page accepted
            // at lift is still provably current. A user-confirmed correction has its own explicit
            // word and is not an automatic admission from the old page.
            if (!guard.saved && !guard.manual && guard.page?.isValid != true) {
                invalidateDefinition(guard)
                endPageGuard()
                return
            }
            retry.visibility = View.GONE; status.text = "保存中…"
            app.worker.execute {
                if (!sessionIsActive() || !gate.accepts(token)) return@execute
                val result = runCatching { app.repository.record(event, entry, replaces, credential) { sessionIsActive() && gate.accepts(token) } }
                main.post {
                    if (sessionIsActive() && gate.accepts(token) && definitionGuard === guard && !guard.invalid) {
                        val error = result.exceptionOrNull()
                        val saved = result.getOrNull() == true
                        // A committed deletion (even before the invalidation message runs) wins over a
                        // late error/success: never re-show retry nor overwrite the invalid text.
                        val stale = definitionCardStale(guard)
                        perf.stage("save", if (saved && !stale) "ok" else "failed")
                        when {
                            stale -> { invalidateDefinition(guard); endPageGuard() }
                            error != null -> {
                                status.text = "保存失败：${error.message}"; status.setTextColor(Paper.error)
                                undo.isEnabled = false; correct.isEnabled = false; retry.visibility = View.VISIBLE
                            }
                            saved -> {
                                guard.saved = true
                                status.text = "已收录 · 可撤销本次查询"; status.setTextColor(Paper.green)
                                undo.isEnabled = true; correct.isEnabled = true; retry.visibility = View.GONE
                                // The record is committed; no further automatic admission depends on
                                // this page. Later page changes never pretend to roll it back.
                                endPageGuard()
                            }
                            else -> { invalidateDefinition(guard); endPageGuard() }
                        }
                    }
                }
            }
        }
        retry.setOnClickListener { save() }
        undo.setOnClickListener {
            // Reject an expired card at the entry too, not only after the worker returns.
            if (definitionCardStale(guard)) { invalidateDefinition(guard); return@setOnClickListener }
            undo.isEnabled = false; correct.isEnabled = false
            app.worker.execute {
                // A no-op revoke (word already deleted / event gone) must not be reported as success.
                val result = runCatching { app.repository.revokeEvent(event.id, entry.key, credential) }
                main.post {
                    if (sessionIsActive() && gate.accepts(token) && definitionGuard === guard) {
                        // Deletion wins over a late revoke result: never re-enable undo/correct nor report REVOKED.
                        if (definitionCardStale(guard)) invalidateDefinition(guard) else {
                            val outcome = result.getOrNull()
                            when {
                                result.isFailure -> {
                                    status.text = "撤销失败：${result.exceptionOrNull()?.message}"
                                    undo.isEnabled = true; correct.isEnabled = true
                                }
                                outcome == RevokeOutcome.REVOKED -> { status.text = "本次查询已撤销；之前的记录保留"; undo.text = "已撤销" }
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
        save()
    }

    private fun card(title: String) = column().apply {
        setPadding(dp(24), dp(24), dp(24), dp(24)); background = shape(Paper.surface, border = true)
        addView(text(title, 26f, bold = true))
    }

    private fun scroll(content: LinearLayout) = ScrollView(this).apply { addView(content); isFillViewport = true }

    private fun sessionIsActive(): Boolean = !stopped && CaptureStatus.generation == sessionGeneration &&
        CaptureStatus.state.phase == CapturePhase.ACTIVE
}
