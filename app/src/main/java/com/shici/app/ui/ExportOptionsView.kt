package com.shici.app.ui

import android.content.Context
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.util.AttributeSet
import android.view.Gravity
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.CheckBox
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import com.shici.app.core.ExportMode
import com.shici.app.core.ExportOptions
import com.shici.app.core.ExportSort
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

/**
 * Native options panel for the PDF export confirmation dialog. It owns only the visual choices
 * (first-collection date range, sort, mode, favorites-only) and their validation; the learned-word
 * multi-selection stays in MainActivity, which can attach keys with [setSelectedKeys].
 *
 * Usage from the integrator:
 * ```
 * val view = ExportOptionsView(this)
 * view.setFavoritesAvailable(favoritesAvailable)   // false until the favorites data is wired
 * view.setOptions(snapshot.options)                // pre-populate from a frozen/default snapshot
 * panel.addView(view)
 * val checked = view.readOptions()                 // options == null -> show checked.error
 * checked.options?.let { options -> ... }          // freeze this copy before starting the export
 * ```
 */
class ExportOptionsView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : LinearLayout(context, attrs) {
    /** Either a validated immutable option set or a user-facing error; never both. */
    data class Validation(val options: ExportOptions?, val error: String?) {
        val isValid: Boolean get() = options != null
    }

    companion object {
        const val DATE_PATTERN = "yyyy-MM-dd"
        private val strictDate = Regex("""\d{4}-\d{2}-\d{2}""")
    }

    private var zone: ZoneId = ZoneId.systemDefault()
    private var selectedKeys: Set<String>? = null
    private var favoritesAvailable = true
    private var updating = false
    private var notifying = false

    private val fromInput = EditText(context).apply {
        hint = DATE_PATTERN
        inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
        isSingleLine = true
    }
    private val toInput = EditText(context).apply {
        hint = DATE_PATTERN
        inputType = InputType.TYPE_CLASS_DATETIME or InputType.TYPE_DATETIME_VARIATION_DATE
        isSingleLine = true
    }
    private val sortSpinner = Spinner(context).apply {
        adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            listOf("最近查询优先", "查询次数优先")
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }
    private val modeSpinner = Spinner(context).apply {
        adapter = ArrayAdapter(
            context,
            android.R.layout.simple_spinner_item,
            listOf("复习版（词＋释义＋笔记来源）", "自测版＋答案页")
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
    }
    private val favoritesCheck = CheckBox(context).apply { text = "仅收藏" }
    private val errorView = TextView(context).apply {
        setTextColor(0xFFB3261E.toInt())
        visibility = View.GONE
    }

    /** Notified on every edit with the same result [readOptions] would return. */
    var onValidationChanged: ((Validation) -> Unit)? = null

    private val textWatcher = object : TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: Editable?) = refresh()
    }
    private val selectionListener = object : AdapterView.OnItemSelectedListener {
        override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) = refresh()
        override fun onNothingSelected(parent: AdapterView<*>?) = Unit
    }

    init {
        orientation = VERTICAL
        addView(label("首次收录日期（$DATE_PATTERN，含首尾自然日；留空不限）"))
        addView(
            LinearLayout(context).apply {
                orientation = HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(fromInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                addView(label(" 至 "))
                addView(toInput, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            }
        )
        addView(spinnerRow("排序：", sortSpinner))
        addView(spinnerRow("内容：", modeSpinner))
        addView(favoritesCheck)
        addView(errorView)

        fromInput.addTextChangedListener(textWatcher)
        toInput.addTextChangedListener(textWatcher)
        sortSpinner.onItemSelectedListener = selectionListener
        modeSpinner.onItemSelectedListener = selectionListener
        favoritesCheck.setOnCheckedChangeListener { _, _ -> refresh() }
        refresh()
    }

    /**
     * Parses and validates the current UI state. On failure [Validation.options] is null and
     * [Validation.error] explains the malformed or reversed date, or the unavailable favorites filter.
     */
    fun readOptions(): Validation {
        val result = validate()
        publish(result)
        return result
    }

    /** Pre-populates the controls (dates, sort, mode, favorites and the zone used for day bounds). */
    fun setOptions(options: ExportOptions) {
        updating = true
        try {
            zone = options.zone
            selectedKeys = options.selectedKeys?.toSet()
            fromInput.setText(options.from?.toString().orEmpty())
            toInput.setText(options.to?.toString().orEmpty())
            sortSpinner.setSelection(if (options.sort == ExportSort.QUERY_COUNT) 1 else 0)
            modeSpinner.setSelection(if (options.mode == ExportMode.SELF_TEST) 1 else 0)
            favoritesCheck.isChecked = options.favoritesOnly
        } finally {
            updating = false
        }
        refresh()
    }

    /**
     * Attaches the explicit key allow-list chosen by MainActivity's word list. `null` means the
     * whole filtered result; an empty set means the user selected nothing. A defensive copy is kept.
     */
    fun setSelectedKeys(keys: Set<String>?) {
        selectedKeys = keys?.toSet()
    }

    /**
     * MainActivity must call this with `false` while favorites are not yet wired to real data, so the
     * panel rejects "仅收藏" instead of letting an empty favorites set silently export nothing.
     */
    fun setFavoritesAvailable(available: Boolean) {
        favoritesAvailable = available
        favoritesCheck.isEnabled = available
        if (!available) {
            updating = true
            try { favoritesCheck.isChecked = false } finally { updating = false }
        }
        refresh()
    }

    private fun validate(): Validation {
        val fromText = fromInput.text.toString().trim()
        val toText = toInput.text.toString().trim()
        val from = if (fromText.isEmpty()) {
            null
        } else {
            parseDate(fromText) ?: return Validation(null, "开始日期格式应为 $DATE_PATTERN")
        }
        val to = if (toText.isEmpty()) {
            null
        } else {
            parseDate(toText) ?: return Validation(null, "结束日期格式应为 $DATE_PATTERN")
        }
        if (from != null && to != null && from.isAfter(to)) {
            return Validation(null, "开始日期不能晚于结束日期")
        }
        if (favoritesCheck.isChecked && !favoritesAvailable) {
            return Validation(null, "仅收藏筛选暂不可用")
        }
        return try {
            Validation(
                ExportOptions(
                    from = from,
                    to = to,
                    sort = if (sortSpinner.selectedItemPosition == 1) {
                        ExportSort.QUERY_COUNT
                    } else {
                        ExportSort.RECENT_QUERY
                    },
                    mode = if (modeSpinner.selectedItemPosition == 1) {
                        ExportMode.SELF_TEST
                    } else {
                        ExportMode.REVIEW
                    },
                    favoritesOnly = favoritesCheck.isChecked,
                    selectedKeys = selectedKeys?.toSet(),
                    zone = zone
                ),
                null
            )
        } catch (e: IllegalArgumentException) {
            Validation(null, e.message ?: "导出选项无效")
        }
    }

    private fun refresh() {
        if (updating) return
        publish(validate())
    }

    private fun publish(result: Validation) {
        errorView.text = result.error.orEmpty()
        errorView.visibility = if (result.error == null) View.GONE else View.VISIBLE
        if (!notifying) {
            notifying = true
            try {
                onValidationChanged?.invoke(result)
            } finally {
                notifying = false
            }
        }
    }

    private fun parseDate(raw: String): LocalDate? {
        if (!strictDate.matches(raw)) return null
        return try {
            LocalDate.parse(raw, DateTimeFormatter.ISO_LOCAL_DATE)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    private fun label(text: String) = TextView(context).apply { this.text = text }

    private fun spinnerRow(text: String, spinner: Spinner) =
        LinearLayout(context).apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(label(text))
            addView(spinner, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
}
