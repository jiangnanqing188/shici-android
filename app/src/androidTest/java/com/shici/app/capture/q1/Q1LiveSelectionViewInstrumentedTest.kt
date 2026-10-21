package com.shici.app.capture.q1

import android.content.Context
import android.view.MotionEvent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.shici.app.capture.LiveSelectionView
import com.shici.app.core.HighlighterStroke
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** UI-object-only tests, no overlay permission, capture, files, database or Activity changes.
 * Compiled for Q1; device execution requires separate authorization and was NOT performed.
 */
@RunWith(AndroidJUnit4::class)
class Q1LiveSelectionViewInstrumentedTest {
 private val ins=InstrumentationRegistry.getInstrumentation()
 private fun onMain(f:()->Unit){ins.runOnMainSync(f)}
 private fun gesture(v:LiveSelectionView,a:Int,x:Float,y:Float){val e=MotionEvent.obtain(0,0,a,x,y,0);try{assertTrue(v.onTouchEvent(e))}finally{e.recycle()}}
 private fun view(c:Context,onStroke:(HighlighterStroke)->Unit={},onShort:()->Unit={},onCancel:(String)->Unit={}):LiveSelectionView=LiveSelectionView(c,1000,1200,onStroke,onShort,onCancel).apply{layout(0,0,1000,1200)}
 @Test fun unarmedViewConsumesWithoutSubmission(){onMain{var n=0;val v=view(ins.targetContext,{n++},{n++},{n++});gesture(v,0,100f,200f);gesture(v,1,500f,200f);assertEquals(0,n)}}
 @Test fun oneStrokeSubmitsOnUpWithoutFinish(){onMain{var n=0;val v=view(ins.targetContext,{n++});v.arm();gesture(v,0,100f,200f);assertEquals(0,n);gesture(v,1,500f,200f);assertEquals(1,n)}}
 @Test fun tapIsNotSubmission(){onMain{var submit=0;var short=0;val v=view(ins.targetContext,{submit++},{short++});v.arm();gesture(v,0,100f,200f);gesture(v,1,100f,200f);assertEquals(0,submit);assertEquals(1,short)}}
 @Test fun cancelledStrokeNeverSubmits(){onMain{var submit=0;var cancel=0;val v=view(ins.targetContext,{submit++},{},{cancel++});v.arm();gesture(v,0,100f,200f);gesture(v,3,500f,200f);assertEquals(0,submit);assertEquals(1,cancel)}}
 @Test fun reversalMaintainsRealEndpointOrder(){onMain{var result:HighlighterStroke?=null;val v=view(ins.targetContext,{result=it});v.arm();gesture(v,0,500f,200f);gesture(v,1,100f,200f);assertNotNull(result);assertTrue(result!!.points.first().x>result!!.points.last().x)}}
 @Test fun moveHistoryNotRequiredForFastTwoPointSwipe(){onMain{var result:HighlighterStroke?=null;val v=view(ins.targetContext,{result=it});v.arm();gesture(v,0,100f,200f);gesture(v,1,800f,200f);assertEquals(2,result!!.points.size)}}
}
