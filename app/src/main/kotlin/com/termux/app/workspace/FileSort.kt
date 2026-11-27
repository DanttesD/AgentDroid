package com.termux.app.workspace

import android.content.Context
import java.util.Comparator

/** One saved display order for the project browser and all of its picker modes.*/
object FileSort {
    enum class Order {
        NAME_ASC,
        NAME_DESC,
        SIZE_DESC,
        SIZE_ASC,
        DATE_DESC,
        DATE_ASC
    }

    @JvmStatic
    fun read(context: Context): Order {
        try {
            return Order.valueOf(context.getSharedPreferences("workspace-files", 0).getString("sort", Order.NAME_ASC.name)!!)
        } catch (error: IllegalArgumentException) {
            return Order.NAME_ASC
        }
    }

    @JvmStatic
    fun save(context: Context, order: Order) {
        context.getSharedPreferences("workspace-files", 0).edit().putString("sort", order.name).apply()
    }

    @JvmStatic
    fun labels(context: Context): Array<String> {
        return if (WorkspaceLanguage.code(context)!!.equals("ru")) arrayOf<String>("Имя · А → Я", "Имя · Я → А", "Размер · сначала большие", "Размер · сначала маленькие", "Дата · сначала новые", "Дата · сначала старые") else arrayOf<String>("Name · A → Z", "Name · Z → A", "Size · largest first", "Size · smallest first", "Date · newest first", "Date · oldest first")
    }

    @JvmStatic
    fun ordered(entries: MutableList<ProjectFiles.Entry>, order: Order): MutableList<ProjectFiles.Entry> {
        val sorted: MutableList<ProjectFiles.Entry> = ArrayList<ProjectFiles.Entry>(entries)
        sorted.sortWith(Comparator<ProjectFiles.Entry> { a, b ->
            if (a.directory != b.directory) {
                return@Comparator if (a.directory) -1 else 1
            }
            var result = 0
            when (order) {
                Order.NAME_DESC -> {
                    return@Comparator -names(a, b)
                }
                Order.SIZE_DESC -> {
                    if (!a.directory) {
                        result = java.lang.Long.compare(b.size, a.size)
                    }
                }
                Order.SIZE_ASC -> {
                    if (!a.directory) {
                        result = java.lang.Long.compare(a.size, b.size)
                    }
                }
                Order.DATE_DESC, Order.DATE_ASC -> {
                    // Old/remote metadata may have no date. Unknown dates stay last in either direction.
                    if (a.modified < 0 != b.modified < 0) {
                        return@Comparator if (a.modified < 0) 1 else -1
                    }
                    result = if (order === Order.DATE_DESC) java.lang.Long.compare(b.modified, a.modified) else java.lang.Long.compare(a.modified, b.modified)
                }
                else -> {
                }
            }
            return@Comparator if (result != 0) result else names(a, b)
        })
        return sorted
    }

    @JvmStatic
    private fun names(a: ProjectFiles.Entry, b: ProjectFiles.Entry): Int {
        var compared: Int = (a.name as java.lang.String).compareToIgnoreCase(b.name)
        if (compared == 0) {
            compared = a.name.compareTo(b.name)
        }
        return if (compared != 0) compared else a.path.compareTo(b.path)
    }
}
