package com.shici.app.ui

import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.shici.app.core.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

/** Detached detail widgets exercise refresh preservation without modifying the user's learning.db. */
@RunWith(AndroidJUnit4::class)
class MainActivityLearningTest {
    private val word = LearnedWord(DemoDictionary.entries.first(), DemoDictionary.SOURCE,
        DemoDictionary.VERSION, "abandoned", 2, 100, 200, false, "已保存笔记")

    private fun descendants(view: View): List<View> = listOf(view) +
        if (view is ViewGroup) (0 until view.childCount).flatMap { descendants(view.getChildAt(it)) } else emptyList()

    private fun detail(activity: MainActivity, content: LinearLayout): Any =
        MainActivity::class.java.getDeclaredMethod("wordDetail", LinearLayout::class.java,
            LearnedWord::class.java, Function0::class.java).apply { isAccessible = true }
            .invoke(activity, content, word, null)!!

    private fun update(binding: Any, word: LearnedWord?) {
        binding.javaClass.getDeclaredMethod("update", LearnedWord::class.java)
            .apply { isAccessible = true }.invoke(binding, word)
    }

    @Test fun committedCountRefreshPreservesUnsavedNoteAndMasteryEvenAfterLastRevocation() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val content = LinearLayout(activity)
                val binding = detail(activity, content)
                val views = descendants(content)
                val note = views.filterIsInstance<EditText>().single()
                val mastery = views.filterIsInstance<Switch>().single()
                note.setText("正在编辑，不得覆盖")
                note.setSelection(3)
                mastery.isChecked = true
                update(binding, word.copy(queryCount = 3, lastAt = 300, note = "库中已更新"))
                assertEquals("正在编辑，不得覆盖", note.text.toString())
                assertEquals(3, note.selectionStart)
                assertTrue(mastery.isChecked)
                assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == "3 次" })
                update(binding, null)
                assertTrue(views.filterIsInstance<TextView>().any { it.text.toString() == "0 次" })
                assertEquals("正在编辑，不得覆盖", note.text.toString())
                assertTrue(mastery.isChecked)
            }
        }
    }

    @Test fun cleanDetailReceivesCommittedNotesAndMasteryInsteadOfKeepingOldValues() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val content = LinearLayout(activity)
                val binding = detail(activity, content)
                update(binding, word.copy(note = "新的已保存笔记", mastered = true))
                val views = descendants(content)
                assertEquals("新的已保存笔记", views.filterIsInstance<EditText>().single().text.toString())
                assertTrue(views.filterIsInstance<Switch>().single().isChecked)
            }
        }
    }
    @Test fun sourceRefreshPreservesUnsavedPersonalFieldsAndUpdatesMeaningAndProvenance() {
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                val content = LinearLayout(activity)
                val binding = detail(activity, content)
                val views = descendants(content)
                val note = views.filterIsInstance<EditText>().single()
                val mastery = views.filterIsInstance<Switch>().single()
                assertTrue(views.filterIsInstance<TextView>().any { it.text.contains(DemoDictionary.VERSION) })
                note.setText("正在编辑的个人笔记")
                mastery.isChecked = true
                val raw = word.entry.copy(meaning = "新词源快照夹具", source = RawKajwebDictionary.SOURCE,
                    version = RawKajwebDictionary.VERSION)
                update(binding, word.copy(entry = raw, source = raw.source, version = raw.version, queryCount = 3))
                assertEquals("正在编辑的个人笔记", note.text.toString())
                assertTrue(mastery.isChecked)
                val labels = views.filterIsInstance<TextView>().map { it.text.toString() }
                assertTrue(labels.any { it.contains("新词源快照夹具") })
                assertTrue(labels.any { it.contains(RawKajwebDictionary.VERSION) && it.contains("未逐条核对") })
                assertFalse(labels.any { it.contains(DemoDictionary.VERSION) })
            }
        }
    }

}
