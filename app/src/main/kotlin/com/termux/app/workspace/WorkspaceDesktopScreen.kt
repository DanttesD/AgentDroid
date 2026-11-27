package com.termux.app.workspace

import android.app.AlertDialog
import android.content.Context
import android.content.DialogInterface
import android.text.InputType
import android.view.View
import android.widget.CheckBox
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import com.termux.x11.X11ScreenSettings
import java.util.Locale
import java.util.function.Consumer

/** Saved X11 geometry only; never restarts a desktop or changes system display settings.*/
object WorkspaceDesktopScreen {
    fun interface Apply {
        fun run(settings: X11ScreenSettings, done: Consumer<String?>)
    }

    @JvmStatic
    fun label(c: Context, target: String): String {
        val s: X11ScreenSettings = X11ScreenSettings.load(c, target)
        return tr(c, "Экран X11: ", "X11 screen: ") + (if (s.automatic()) tr(c, "Авто", "Auto") else s.effectiveWidth().toString() + " × " + s.height)
    }

    @JvmStatic
    fun show(c: Context, target: String, saved: Runnable, back: Runnable) {
        show(c, target, saved, back, Apply { settings, done -> done.accept(null) })
    }

    @JvmStatic
    fun show(c: Context, target: String, saved: Runnable, back: Runnable, apply: Apply) {
        val s: X11ScreenSettings = X11ScreenSettings.load(c, target)
        val body: LinearLayout = AgentStatusUi.body(c)
        val automatic = CheckBox(c)
        automatic.setText(tr(c, "Автонастройка", "Auto setup"))
        automatic.setTextColor(Pal.TEXT)
        automatic.setTag("x11-screen-auto")
        automatic.setChecked(s.automatic() && s.horizontalDp == 0 && s.verticalDp == 0 && s.scalePercent == 100)
        body.addView(automatic)
        val dimensions = LinearLayout(c)
        val width: EditText = number(dimensions, tr(c, "Ширина, px", "Width, px"), "x11-screen-width", if (s.automatic()) 1600 else s.width)
        val height: EditText = number(dimensions, tr(c, "Высота, px", "Height, px"), "x11-screen-height", if (s.automatic()) 720 else s.height)
        body.addView(dimensions)
        AgentStatusUi.info(body, tr(c, "Размер сохраняется для этого окружения. Вручную: 320–4096 × 200–4096, не более 8 Мп. X11 выравнивает ширину по 8 пикселей: 1300 → 1296. Разрешение поворачивается вместе с телефоном. Изображение растягивается на всю область рабочего стола, кроме заданных ниже отступов.", "Saved for this environment. Manual: 320–4096 × 200–4096, up to 8 MP. X11 aligns width to 8 pixels: 1300 → 1296. The resolution rotates with the phone. The image stretches to fill the desktop area, apart from the margins below."))
        val scaling = LinearLayout(c)
        val scale: EditText = number(scaling, tr(c, "Масштаб интерфейса, %", "Interface scale, %"), "x11-screen-scale", s.scalePercent)
        body.addView(scaling)
        AgentStatusUi.info(body, tr(c, "50–150%. Меньше — мельче шрифты, панели, значки и новые окна файлового менеджера. Применяется по кнопке «Применить». Некоторые открытые программы потребуется открыть заново. 100% — исходный размер. При отключении авто подставляется 1600×720 и 75%.", "50–150%. Lower values reduce fonts, panels, icons and new file manager windows. Applied with Apply. Some open applications may need to be reopened. 100% restores the original size. Disabling auto fills in 1600×720 and 75%."))
        AgentStatusUi.info(body, tr(c, "Отступы с каждой стороны (dp)", "Margins on each side (dp)"))
        val margins = LinearLayout(c)
        val horizontal: EditText = number(margins, tr(c, "Слева / справа", "Left / right"), "x11-screen-horizontal", s.horizontalDp)
        val vertical: EditText = number(margins, tr(c, "Сверху / снизу", "Top / bottom"), "x11-screen-vertical", s.verticalDp)
        body.addView(margins)
        AgentStatusUi.info(body, tr(c, "От 0 до 96 dp. Защищают края от скруглений и вырезов; на маленьком окне ограничиваются четвертью его размера. Настройка действует в окне и на полном экране, без остановки стола.", "0–96 dp. Keep content away from rounded corners and cutouts; limited to a quarter of a small window. Applied in embedded and full-screen views without restarting the desktop."))
        AgentStatusUi.info(body, tr(c, "Автонастройка возвращает автоматическое разрешение, масштаб 100% и нулевые отступы. Ручные поля недоступны. Изменения применяются по кнопке «Применить».", "Auto setup restores automatic resolution, 100% scale and zero margins. Manual fields are disabled. Changes take effect with Apply."))
        val automaticChanged = Consumer<Boolean?> { on ->
            if (on!!) {
                width.setText("")
                height.setText("")
                width.setHint(tr(c, "Авто", "Auto"))
                height.setHint(tr(c, "Авто", "Auto"))
                scale.setText(java.lang.String.format(Locale.ROOT, "%d", 100))
                horizontal.setText(java.lang.String.format(Locale.ROOT, "%d", 0))
                vertical.setText(java.lang.String.format(Locale.ROOT, "%d", 0))
            } else if ((width.getText().toString() as java.lang.String).trim().isEmpty() || (height.getText().toString() as java.lang.String).trim().isEmpty()) {
                val fields: Array<EditText> = arrayOf<EditText>(width, height, scale, horizontal, vertical)
                val values: IntArray = intArrayOf(1600, 720, 75, 5, 5)
                for (i in 0 until (fields.size)) {
                    fields[i].setText(java.lang.String.format(Locale.ROOT, "%d", values[i]))
                }
            }
            for (field in arrayOf<EditText>(width, height, scale, horizontal, vertical)) {
                field.setEnabled(!on)
                (field.getParent() as View).setAlpha(if (on) 0.4f else 1f)
                if (on) {
                    field.clearFocus()
                }
            }
        }
        automatic.setOnCheckedChangeListener(CompoundButton.OnCheckedChangeListener { b, on -> automaticChanged.accept(on) })
        automaticChanged.accept(automatic.isChecked())
        val error: TextView = AgentStatusUi.info(body, "")
        error.setTextColor(Pal.DANGER)
        error.setTag("x11-screen-error")
        error.setVisibility(View.GONE)
        val dialog: AlertDialog = WorkspaceDialogs.builder(c).setTitle(tr(c, "Экран X11", "X11 screen")).setView(AgentStatusUi.scroll(body)).setNegativeButton(tr(c, "Отмена", "Cancel"), DialogInterface.OnClickListener { d, w -> back.run() }).setPositiveButton(tr(c, "Применить", "Apply"), null).create()
        dialog.setOnCancelListener(DialogInterface.OnCancelListener { d -> back.run() })
        dialog.show()
        dialog.getButton(DialogInterface.BUTTON_POSITIVE).setOnClickListener(View.OnClickListener { v ->
            try {
                val next: X11ScreenSettings = if (automatic.isChecked()) X11ScreenSettings.defaults() else X11ScreenSettings(parse(width), parse(height), parse(horizontal), parse(vertical), parse(scale))
                dialog.getButton(-1).setEnabled(false)
                dialog.getButton(-2).setEnabled(false)
                dialog.getButton(-1).setText(tr(c, "Применяется…", "Applying…"))
                dialog.setCancelable(false)
                error.setVisibility(View.GONE)
                automatic.setEnabled(false)
                for (field in arrayOf<EditText>(width, height, scale, horizontal, vertical)) {
                    field.setEnabled(false)
                }
                apply.run(next, Consumer<String?> { message ->
                    if (!dialog.isShowing()) {
                        return@Consumer
                    }
                    if (message == null) {
                        next.save(c, target)
                        dialog.dismiss()
                        saved.run()
                    } else {
                        dialog.getButton(-1).setEnabled(true)
                        dialog.getButton(-2).setEnabled(true)
                        dialog.getButton(-1).setText(tr(c, "Применить", "Apply"))
                        dialog.setCancelable(true)
                        automatic.setEnabled(true)
                        automaticChanged.accept(automatic.isChecked())
                        error.setText(message)
                        error.setVisibility(View.VISIBLE)
                    }
                })
            } catch (invalid: IllegalArgumentException) {
                error.setText(tr(c, "Проверьте разрешение и отступы: размер от 320×200 до 4096×4096, до 8 Мп; отступы 0–96 dp; масштаб 50–150%.", "Check the values: resolution from 320×200 to 4096×4096, up to 8 MP; margins 0–96 dp; scale 50–150%."))
                error.setVisibility(View.VISIBLE)
            }
        })
    }

    @JvmStatic
    private fun parse(field: EditText): Int {
        return Integer.parseInt((field.getText().toString() as java.lang.String).trim())
    }

    @JvmStatic
    private fun number(parent: LinearLayout, label: String, tag: String, value: Int): EditText {
        val c: Context = parent.getContext()
        val column = LinearLayout(c)
        column.setOrientation(LinearLayout.VERTICAL)
        AgentStatusUi.info(column, label)
        val input = EditText(c)
        input.setTag(tag)
        input.setContentDescription(label)
        input.setTextColor(Pal.TEXT)
        input.setSingleLine(true)
        input.setInputType(InputType.TYPE_CLASS_NUMBER)
        input.setText(java.lang.String.format(Locale.ROOT, "%d", value))
        column.addView(input, LinearLayout.LayoutParams(-1, -2))
        parent.addView(column, LinearLayout.LayoutParams(0, -2, 1.0f))
        return input
    }

    @JvmStatic
    private fun tr(c: Context, ru: String, en: String): String {
        return if (WorkspaceLanguage.code(c)!!.equals("ru")) ru else en
    }
}
